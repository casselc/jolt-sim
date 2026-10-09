// Command celgo is a reference CEL evaluator backed by github.com/google/cel-go.
//
// It reads JSON lines on stdin, one request per line:
//
//	{"expr": "...", "bindings": {"x": <value>}, "disable_macros": false,
//	 "disable_check": false, "id": <anything, echoed back>}
//
// and writes one JSON line per request:
//
//	{"value": <value>, "cost": n, "estimate": {"min": n, "max": n}}
//	{"error": "...", "stage": "parse|check|program|eval|input", "cost": n, ...}
//
// Values use the conformance corpus encoding (see ../conformance/README.md):
// {"int": "1"}, {"uint": "1"}, {"double": 1.5 | "NaN" | "Infinity" |
// "-Infinity" | "-0"}, {"string": ""}, {"bytes": "<base64>"}, {"bool": true},
// {"null": null}, {"list": [...]}, {"map": [[k, v], ...]}, {"type": "int"},
// {"timestamp": "RFC3339"}, {"duration": "1.5s"}, {"object": {...}}.
// cel-go-only results are additionally encoded as {"optional": <value>|null}
// and {"unknown": [ids]}.
//
// Every binding is declared as cel.Variable(name, cel.DynType). Map entries
// in the output are sorted by the JSON encoding of their key.
package main

import (
	"bufio"
	"encoding/base64"
	"encoding/json"
	"flag"
	"fmt"
	"math"
	"os"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/google/cel-go/cel"
	"github.com/google/cel-go/checker"
	"github.com/google/cel-go/common/types"
	"github.com/google/cel-go/common/types/ref"
	"github.com/google/cel-go/common/types/traits"
	"github.com/google/cel-go/ext"
	"google.golang.org/protobuf/encoding/protojson"
	"google.golang.org/protobuf/proto"
)

type request struct {
	ID            json.RawMessage            `json:"id,omitempty"`
	Expr          string                     `json:"expr"`
	Bindings      map[string]json.RawMessage `json:"bindings"`
	DisableMacros bool                       `json:"disable_macros"`
	DisableCheck  bool                       `json:"disable_check"`
}

type estimate struct {
	Min uint64 `json:"min"`
	Max uint64 `json:"max"`
}

type response struct {
	ID       json.RawMessage `json:"id,omitempty"`
	Value    any             `json:"value,omitempty"`
	Error    *string         `json:"error,omitempty"`
	Stage    string          `json:"stage,omitempty"`
	Cost     *uint64         `json:"cost,omitempty"`
	Estimate *estimate       `json:"estimate,omitempty"`
}

// nilEstimator provides no size hints: dyn inputs get cel-go's default bounds.
type nilEstimator struct{}

func (nilEstimator) EstimateSize(checker.AstNode) *checker.SizeEstimate { return nil }
func (nilEstimator) EstimateCallCost(string, string, *checker.AstNode, []checker.AstNode) *checker.CallEstimate {
	return nil
}

func baseOptions(profile string) []cel.EnvOption {
	opts := []cel.EnvOption{
		ext.Strings(),
		ext.Math(),
		ext.Lists(),
		ext.Sets(),
		ext.TwoVarComprehensions(),
	}
	if profile == "conformance" {
		// Closer to cel-go's own conformance runner, for the extra corpus files.
		opts = append(opts,
			cel.OptionalTypes(),
			ext.Bindings(),
			ext.Encoders(),
			ext.Network(),
			cel.EnableIdentifierEscapeSyntax(),
			cel.EnableErrorOnBadPresenceTest(true),
		)
	}
	return opts
}

func main() {
	profile := flag.String("profile", "default",
		"default: stdlib + ext.Strings/Math/Lists/Sets/TwoVarComprehensions; "+
			"conformance: also OptionalTypes, Bindings, Encoders, Network, identifier escapes, error on bad presence test")
	flag.Parse()
	if *profile != "default" && *profile != "conformance" {
		fmt.Fprintln(os.Stderr, "unknown -profile", *profile)
		os.Exit(2)
	}

	envMacros, err := cel.NewEnv(baseOptions(*profile)...)
	if err != nil {
		fmt.Fprintln(os.Stderr, "env:", err)
		os.Exit(1)
	}
	// ClearMacros must come last: the extension libraries register macros too.
	envNoMacros, err := envMacros.Extend(cel.ClearMacros())
	if err != nil {
		fmt.Fprintln(os.Stderr, "env:", err)
		os.Exit(1)
	}

	in := bufio.NewScanner(os.Stdin)
	in.Buffer(make([]byte, 1<<20), 64<<20)
	out := bufio.NewWriter(os.Stdout)
	defer out.Flush()
	enc := json.NewEncoder(out)
	enc.SetEscapeHTML(false)
	for in.Scan() {
		line := strings.TrimSpace(in.Text())
		if line == "" {
			continue
		}
		var req request
		var resp response
		if err := json.Unmarshal([]byte(line), &req); err != nil {
			resp = errResp("input", "bad request: "+err.Error())
		} else {
			base := envMacros
			if req.DisableMacros {
				base = envNoMacros
			}
			resp = run(base, &req)
			resp.ID = req.ID
		}
		if err := enc.Encode(resp); err != nil {
			fmt.Fprintln(os.Stderr, "encode:", err)
			os.Exit(1)
		}
		out.Flush()
	}
	if err := in.Err(); err != nil {
		fmt.Fprintln(os.Stderr, "read:", err)
		os.Exit(1)
	}
}

func errResp(stage, msg string) response {
	return response{Error: &msg, Stage: stage}
}

func run(base *cel.Env, req *request) response {
	names := make([]string, 0, len(req.Bindings))
	for k := range req.Bindings {
		names = append(names, k)
	}
	sort.Strings(names)
	decls := make([]cel.EnvOption, 0, len(names))
	for _, n := range names {
		decls = append(decls, cel.Variable(n, cel.DynType))
	}
	env, err := base.Extend(decls...)
	if err != nil {
		return errResp("input", err.Error())
	}
	act := make(map[string]any, len(names))
	for _, n := range names {
		v, err := decodeValue(env, req.Bindings[n])
		if err != nil {
			return errResp("input", fmt.Sprintf("binding %q: %v", n, err))
		}
		act[n] = v
	}

	parsed, iss := env.Parse(req.Expr)
	if iss.Err() != nil {
		return errResp("parse", iss.Err().Error())
	}
	var est *estimate
	ast := parsed
	checked, iss := env.Check(parsed)
	if iss.Err() == nil {
		if e, err := env.EstimateCost(checked, nilEstimator{}); err == nil {
			est = &estimate{Min: e.Min, Max: e.Max}
		}
		if !req.DisableCheck {
			ast = checked
		}
	} else if !req.DisableCheck {
		return errResp("check", iss.Err().Error())
	}

	prg, err := env.Program(ast, cel.CostTracking(nil))
	if err != nil {
		r := errResp("program", err.Error())
		r.Estimate = est
		return r
	}
	val, det, err := prg.Eval(act)
	var resp response
	if err != nil {
		resp = errResp("eval", err.Error())
	} else if types.IsError(val) {
		resp = errResp("eval", val.(*types.Err).String())
	} else {
		enc, eerr := encodeValue(val)
		if eerr != nil {
			resp = errResp("output", eerr.Error())
		} else {
			resp.Value = enc
		}
	}
	if det != nil {
		resp.Cost = det.ActualCost()
	}
	resp.Estimate = est
	return resp
}

// ---------------------------------------------------------------------------
// decoding: corpus JSON value -> ref.Val

func decodeValue(env *cel.Env, raw json.RawMessage) (ref.Val, error) {
	var obj map[string]json.RawMessage
	if err := json.Unmarshal(raw, &obj); err != nil {
		return nil, err
	}
	if len(obj) != 1 {
		return nil, fmt.Errorf("value must have exactly one key, got %d", len(obj))
	}
	for k, v := range obj {
		switch k {
		case "int":
			var s string
			if err := json.Unmarshal(v, &s); err != nil {
				return nil, err
			}
			i, err := strconv.ParseInt(s, 10, 64)
			if err != nil {
				return nil, err
			}
			return types.Int(i), nil
		case "uint":
			var s string
			if err := json.Unmarshal(v, &s); err != nil {
				return nil, err
			}
			u, err := strconv.ParseUint(s, 10, 64)
			if err != nil {
				return nil, err
			}
			return types.Uint(u), nil
		case "double":
			var s string
			if json.Unmarshal(v, &s) == nil {
				switch s {
				case "NaN":
					return types.Double(math.NaN()), nil
				case "Infinity":
					return types.Double(math.Inf(1)), nil
				case "-Infinity":
					return types.Double(math.Inf(-1)), nil
				case "-0":
					return types.Double(math.Copysign(0, -1)), nil
				}
				return nil, fmt.Errorf("bad double %q", s)
			}
			var f float64
			if err := json.Unmarshal(v, &f); err != nil {
				return nil, err
			}
			return types.Double(f), nil
		case "string":
			var s string
			if err := json.Unmarshal(v, &s); err != nil {
				return nil, err
			}
			return types.String(s), nil
		case "bytes":
			var s string
			if err := json.Unmarshal(v, &s); err != nil {
				return nil, err
			}
			b, err := base64.StdEncoding.DecodeString(s)
			if err != nil {
				return nil, err
			}
			return types.Bytes(b), nil
		case "bool":
			var b bool
			if err := json.Unmarshal(v, &b); err != nil {
				return nil, err
			}
			return types.Bool(b), nil
		case "null":
			return types.NullValue, nil
		case "list":
			var elems []json.RawMessage
			if err := json.Unmarshal(v, &elems); err != nil {
				return nil, err
			}
			vals := make([]ref.Val, len(elems))
			for i, e := range elems {
				ev, err := decodeValue(env, e)
				if err != nil {
					return nil, err
				}
				vals[i] = ev
			}
			return types.NewRefValList(env.CELTypeAdapter(), vals), nil
		case "map":
			var entries [][2]json.RawMessage
			if err := json.Unmarshal(v, &entries); err != nil {
				return nil, err
			}
			m := make(map[ref.Val]ref.Val, len(entries))
			for _, e := range entries {
				kv, err := decodeValue(env, e[0])
				if err != nil {
					return nil, err
				}
				vv, err := decodeValue(env, e[1])
				if err != nil {
					return nil, err
				}
				m[kv] = vv
			}
			return types.NewRefValMap(env.CELTypeAdapter(), m), nil
		case "type":
			var s string
			if err := json.Unmarshal(v, &s); err != nil {
				return nil, err
			}
			if t, ok := env.CELTypeProvider().FindIdent(s); ok {
				return t, nil
			}
			return nil, fmt.Errorf("unknown type %q", s)
		case "timestamp":
			var s string
			if err := json.Unmarshal(v, &s); err != nil {
				return nil, err
			}
			t, err := time.Parse(time.RFC3339Nano, s)
			if err != nil {
				return nil, err
			}
			return types.Timestamp{Time: t.UTC()}, nil
		case "duration":
			var s string
			if err := json.Unmarshal(v, &s); err != nil {
				return nil, err
			}
			d, err := time.ParseDuration(s)
			if err != nil {
				return nil, err
			}
			return types.Duration{Duration: d}, nil
		default:
			return nil, fmt.Errorf("unsupported value kind %q", k)
		}
	}
	panic("unreachable")
}

// ---------------------------------------------------------------------------
// encoding: ref.Val -> corpus JSON value

func encDouble(f float64) any {
	switch {
	case math.IsNaN(f):
		return "NaN"
	case math.IsInf(f, 1):
		return "Infinity"
	case math.IsInf(f, -1):
		return "-Infinity"
	case f == 0 && math.Signbit(f):
		return "-0"
	}
	return f
}

// fracDigits formats nanos the way the protobuf JSON mapping does: 0, 3, 6 or
// 9 fractional digits.
func fracDigits(nanos int64) string {
	switch {
	case nanos == 0:
		return ""
	case nanos%1000000 == 0:
		return fmt.Sprintf(".%03d", nanos/1000000)
	case nanos%1000 == 0:
		return fmt.Sprintf(".%06d", nanos/1000)
	}
	return fmt.Sprintf(".%09d", nanos)
}

func fmtTimestamp(t time.Time) string {
	t = t.UTC()
	return t.Format("2006-01-02T15:04:05") + fracDigits(int64(t.Nanosecond())) + "Z"
}

func fmtDuration(d time.Duration) string {
	n := int64(d)
	sign := ""
	if n < 0 {
		sign = "-"
		if n == math.MinInt64 {
			return "-9223372036.854775808s"
		}
		n = -n
	}
	return fmt.Sprintf("%s%d%ss", sign, n/1e9, fracDigits(n%1e9))
}

func encodeValue(v ref.Val) (any, error) {
	switch x := v.(type) {
	case types.Int:
		return map[string]any{"int": strconv.FormatInt(int64(x), 10)}, nil
	case types.Uint:
		return map[string]any{"uint": strconv.FormatUint(uint64(x), 10)}, nil
	case types.Double:
		return map[string]any{"double": encDouble(float64(x))}, nil
	case types.String:
		return map[string]any{"string": string(x)}, nil
	case types.Bytes:
		return map[string]any{"bytes": base64.StdEncoding.EncodeToString([]byte(x))}, nil
	case types.Bool:
		return map[string]any{"bool": bool(x)}, nil
	case types.Null:
		return map[string]any{"null": nil}, nil
	case types.Timestamp:
		return map[string]any{"timestamp": fmtTimestamp(x.Time)}, nil
	case types.Duration:
		return map[string]any{"duration": fmtDuration(x.Duration)}, nil
	case *types.Type:
		return map[string]any{"type": x.TypeName()}, nil
	case *types.Optional:
		if !x.HasValue() {
			return map[string]any{"optional": nil}, nil
		}
		inner, err := encodeValue(x.GetValue())
		if err != nil {
			return nil, err
		}
		return map[string]any{"optional": inner}, nil
	case *types.Unknown:
		ids := x.IDs()
		return map[string]any{"unknown": ids}, nil
	}
	if l, ok := v.(traits.Lister); ok && v.Type() == types.ListType {
		n := int(l.Size().(types.Int))
		elems := make([]any, 0, n)
		for i := 0; i < n; i++ {
			e, err := encodeValue(l.Get(types.Int(i)))
			if err != nil {
				return nil, err
			}
			elems = append(elems, e)
		}
		return map[string]any{"list": elems}, nil
	}
	if m, ok := v.(traits.Mapper); ok && v.Type() == types.MapType {
		type entry struct {
			key  string
			pair [2]any
		}
		var entries []entry
		it := m.Iterator()
		for it.HasNext() == types.True {
			k := it.Next()
			ke, err := encodeValue(k)
			if err != nil {
				return nil, err
			}
			ve, err := encodeValue(m.Get(k))
			if err != nil {
				return nil, err
			}
			kb, _ := json.Marshal(ke)
			entries = append(entries, entry{string(kb), [2]any{ke, ve}})
		}
		sort.Slice(entries, func(i, j int) bool { return entries[i].key < entries[j].key })
		pairs := make([]any, len(entries))
		for i, e := range entries {
			pairs[i] = e.pair
		}
		return map[string]any{"map": pairs}, nil
	}
	if pm, ok := v.Value().(proto.Message); ok {
		b, err := protojson.Marshal(pm)
		if err != nil {
			return nil, err
		}
		return map[string]any{"object": map[string]any{
			"type_url": "type.googleapis.com/" + string(pm.ProtoReflect().Descriptor().FullName()),
			"json":     json.RawMessage(b),
		}}, nil
	}
	return nil, fmt.Errorf("cannot encode value of type %s", v.Type().TypeName())
}
