// Copyright (c) 2014-2018 K Team. All Rights Reserved.
module ISSUE
  syntax Target ::= "foo"

  rule T::Target => 0 when T =/=K 0
endmodule
