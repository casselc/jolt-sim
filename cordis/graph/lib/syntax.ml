(* The graph language's syntax tree (docs/cordis/rfc/0002-graph-language.md,
   "Concrete syntax"). Every expression is CEL: it keeps its canonical text
   (its tokens printed with fixed spacing, which is what hashes cover) and
   its parsed tree. *)

type ty =
  | Scalar of string * int option  (** int, uint, double, string, bytes, bool, timestamp, duration, dyn; <max N> *)
  | List of ty * int option
  | Map of ty * ty * int option
  | Option of ty
  | Record of (string * ty) list * bool  (** fields; open (..) *)
  | Named of string
  | Var of string  (** 'a *)

type expr =
  | Cel of { text : string; ast : Cordis_cel.Ast.expr }
  | Hole of string  (** ?name *)

type stmt =
  | Let of string * expr
  | Use of string * string * expr  (** use x = kind(arg) *)

type cell = {
  name : string;
  params : (string * ty) list;
  result : ty;
  effects : string list;
  state : (string * ty * expr) list;  (** field, type, initial value *)
  body : stmt list;
  final : expr;
  updates : (string * expr) list;  (** with state.f := e *)
}

type fresh = On_change | Always | Every of string

type pattern = Case of string * string option  (** Tag, Tag(x), Tag(_) *) | Wild

type action = Enable of string | Append of string * expr | Nothing

type asset = { name : string; cell : string; args : string list; fresh : fresh option }

type gitem =
  | Input of string * ty
  | Stream of string * ty * int  (** element type, capacity: up to N *)
  | Asset of asset
  | Route of string * (pattern * action list) list
  | Before of string * string
  | Always_inv of expr
  | Sometimes_inv of expr

type tdef = Alias of ty | Variant of (string * ty option) list

type item =
  | Uses of string * string  (** alias, sha256:... *)
  | Uses_descriptors of string  (** a FileDescriptorSet, by sha256:... (RFC 0004) *)
  | Type_def of string * tdef * int option  (** name, definition, <nodes N>: a recursive value's node bound *)
  | Effect of string * ty * ty  (** kind, request, receipt *)
  | Cell of cell
  | Graph of string * gitem list

type file = item list

let item_name = function
  | Uses (a, _) -> a
  | Uses_descriptors h -> h
  | Type_def (n, _, _) | Effect (n, _, _) | Graph (n, _) -> n
  | Cell c -> c.name
