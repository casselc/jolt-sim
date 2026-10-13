(* llm.complete through halo's operation API (docs/cordis/rfc/0007-boundaries.md, "Verbs", "Status answers"): the
   adapter over Op_client's envelope. It says what the request is (a chat completion), what each status means, and
   what a fetched result is and costs; the envelope does the rest.

   What each status does (RFC 0007):
   - running: wait;           never_accepted: send the same submission again (not terminal);
   - done: fetch, journal, then acknowledge (the envelope's order);
   - closed, cancelled_before_accept: terminal, nothing spent: Refused;
   - unknown, released: the work is beyond reach: a lost receipt (charged the worst case);
   - expired: the body is gone, the usage is not: settled at that usage. *)

module V = Cordis_cel.Value
module J = Yojson.Safe
open Executors
open Op_client

type cfg = {
  url : string;
  principal : string;  (** X-Halo-Principal: the owner of the attempts *)
  mode : string;  (** "relaxed" or "exact": what the intent needs, which the server must run *)
  window_ms : int;  (** how long, by halo's clock, a submit may still be accepted *)
  poll_s : float;
  wait_s : float;
}

let default_cfg ~url ~principal = { url; principal; mode = "relaxed"; window_ms = 120_000; poll_s = 0.1; wait_s = 300. }

let client_of (c : cfg) : Op_client.cfg = { url = c.url; principal = c.principal; window_ms = c.window_ms; poll_s = c.poll_s; wait_s = c.wait_s }

let tokens_of v =
  match J.Util.member "usage" v with
  | `Assoc _ as u -> Option.value (int_ u "prompt_tokens") ~default:0 + Option.value (int_ u "completion_tokens") ~default:0
  | _ -> 0

let adapter (c : cfg) : Op_client.adapter =
  { contract_path = "/v1/halo/contract";
    check_contract =
      (fun contract ->
        match J.Util.member "inference" contract with
        | `Assoc _ as i -> (
            match str i "mode" with
            | Some m when m <> c.mode -> raise (Refused (Printf.sprintf "mode_unavailable: the server runs %s, the intent needs %s" m c.mode))
            | _ -> ())
        | _ -> ());
    request =
      (fun ~op ~attempt ~not_after req ->
        let fields = List.filter (fun (k, _) -> k <> "model") (chat_fields req) in
        J.to_string
          (`Assoc
            [ ("operation_id", `String op); ("attempt", `Int attempt); ("not_after", `Int not_after); ("api", `String "chat");
              ("request", `Assoc fields); ("mode", `String c.mode) ]));
    interpret =
      (fun v ->
        match str v "status" with
        | Some "running" -> Wait
        | Some "never_accepted" -> Resend
        | Some "done" -> Take
        | Some ("closed" | "cancelled_before_accept") -> Refuse "closed: no copy was accepted"
        | Some "expired" -> Unusable ("expired: the capture window passed before the result was taken", tokens_of v)
        | Some "released" -> Lose "released: acknowledged earlier, the result is no longer held"
        | Some "unknown" -> Lose "unknown: another incarnation"
        | st -> Fail ("status: " ^ Option.value st ~default:"none"));
    decode =
      (fun ~view ~status ~result ->
        let content, _, _, reasoning = read_completion result in
        let finish = Option.value (str view "finish") ~default:"" in
        let tokens = tokens_of view in
        { value = completion_value content finish reasoning; cost = (if tokens > 0 then tokens else -1); source = Some "operation";
          discarded = None; admitted = None; world = world_of status; owes = None; committed = None }) }

let llm_complete ~store (c : cfg) = Op_client.executor ~store (client_of c) (adapter c)
