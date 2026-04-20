package com.kingmang.ixion.exception

class ExternNotFoundException : IxException(
    4,
    "External object `{0}` not found.",
    "Ensure the external type you are referencing actually exists."
)