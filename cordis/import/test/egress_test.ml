(* The egress allowlist read from an OpenAPI document's servers
   (Openapi.endpoint, Openapi.allowlist; docs/cordis/rfc/0003-types-
   importers-scaffolds.md, revision 4): cases, and a Hegel property that an
   endpoint written as a URL reads back as itself. *)

open Cordis_import

let show = function
  | None -> "none"
  | Some (e : Openapi.endpoint) -> Printf.sprintf "%s %s %d %s" e.scheme e.host e.port e.base

let cases =
  [ ("https://api.example.com/v1", "https api.example.com 443 /v1");
    ("http://example.com", "http example.com 80 /");
    ("HTTPS://API.Example.COM:8443/a/b", "https api.example.com 8443 /a/b");
    ("https://[::1]:9000/x", "https [::1] 9000 /x");
    ("https://[::1]/x", "https [::1] 443 /x");
    (* no host: a deployment's --api-base says where to send *)
    ("/v1", "none");
    ("v1/api", "none");
    (* a credential in the URL, a scheme with no default port, an unreadable port *)
    ("https://user:pw@example.com/", "none");
    ("ftp://example.com/", "none");
    ("https://example.com:http/", "none") ]

let roundtrip () =
  let settings = Hegel.Settings.create ~test_cases:500 ~seed:3 () in
  Hegel.run_hegel_test ~settings ~database_key:"cordis:import:egress" (fun tc ->
      let pick l = List.nth l (Hegel.draw_silent tc (Hegel.Generators.integers ~min_value:0 ~max_value:(List.length l - 1) ())) in
      let scheme = pick [ "http"; "https" ] in
      let host = pick [ "example.com"; "api.example.co.uk"; "10.0.0.1"; "[2001:db8::1]"; "localhost" ] in
      let port = Hegel.draw_silent tc (Hegel.Generators.integers ~min_value:1 ~max_value:65535 ()) in
      let base = pick [ "/"; "/v1"; "/api/v2/"; "/a%20b" ] in
      let default = (scheme = "https" && port = 443) || (scheme = "http" && port = 80) in
      let url = Printf.sprintf "%s://%s%s%s" scheme host (if default && pick [ true; false ] then "" else ":" ^ string_of_int port) base in
      let want = Some { Openapi.scheme; host; port; base } in
      Hegel.note tc url;
      Hegel.require tc ~msg:(Printf.sprintf "%s read as %s" url (show (Openapi.endpoint url))) (Openapi.endpoint url = want))

let () =
  Alcotest.run "egress"
    [ ( "endpoints",
        List.map (fun (u, want) -> Alcotest.test_case u `Quick (fun () -> Alcotest.(check string) u want (show (Openapi.endpoint u)))) cases );
      ("properties", [ Alcotest.test_case "an endpoint written as a URL reads back as itself" `Quick roundtrip ]) ]
