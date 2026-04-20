package com.kingmang.ixion.exception

class FieldNotPresentException : IxException(
    5,
    "Field `{0}` not present on type `{1}`.",
    null
)