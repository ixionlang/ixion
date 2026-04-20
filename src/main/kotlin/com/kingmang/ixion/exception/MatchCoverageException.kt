package com.kingmang.ixion.exception

class MatchCoverageException : IxException(
    11,
    "Not all entries in UnionType `{0}` are covered by this match statement. Add cases for {1}.",
    "All possible values of a UnionType must be handled."
)