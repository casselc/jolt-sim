(* The CEL front door: parse, then evaluate (docs/cordis/rfc/0001-cel.md). *)

module Value = Value
module Ast = Ast
module Parser = Parser
module Eval = Eval

let parse = Parser.parse

let eval ?limit ?(vars = []) ?(macros = true) src =
  match Parser.parse ~macros src with
  | Error e -> Error e
  | Ok ast -> Ok (Eval.run ?limit ~vars ast)
