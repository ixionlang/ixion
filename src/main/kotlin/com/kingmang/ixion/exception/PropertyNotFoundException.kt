package com.kingmang.ixion.exception

class PropertyNotFoundException : IxException(
    17,
    "Type `{0}` contains no field `{1}`.",
    null
)