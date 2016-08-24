// "Create type parameter 'X'" "true"
// ERROR: Unresolved reference: _
class A<T : List<T>>

interface I : List<I>

fun foo(x: A<<caret>X>) {

}

fun test() {
    foo(A())
    foo(A<I>())
}