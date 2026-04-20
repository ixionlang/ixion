package com.kingmang.ixion.exception

class ModuleNotFoundException : IxException(
    13,
    "Module `{0}` is not found.",
    "Is the module misspelled?"
)