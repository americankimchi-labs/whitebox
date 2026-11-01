package com.americankimchilabs.whitebox.server

/** Prints a new server-key secret value (`./gradlew -q :server:newServerKey`), for WHITEBOX_SERVER_KEY. */
fun main() = println(ServerKey.secret())
