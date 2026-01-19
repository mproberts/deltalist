package com.latenighthack.deltalist

sealed class Mutation {
    data class Insert(val index: Int, val count: Int = 1) : Mutation()
    data class Remove(val index: Int, val count: Int = 1) : Mutation()
    data class Update(val index: Int, val count: Int = 1) : Mutation()
    data class Move(val fromIndex: Int, val toIndex: Int, val count: Int = 1) : Mutation()
}
