(* The extension libraries, each in its own module, registered once. *)

let loaded = ref false

let load () =
  if not !loaded then begin
    loaded := true;
    Ext_cel.register ();
    Ext_optional.register ();
    Ext_proto.register ();
    Ext_strings.register ();
    Ext_math.register ();
    Ext_lists.register ();
    Ext_network.register ();
    Ext_encoders.register ()
  end
