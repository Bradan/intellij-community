// AFTER-WARNING: Variable 'foo' is never used
// IGNORE_K2
fun <T> foo(x: T & Any) {
    val foo: <caret>() -> T & Any = { x }
}
