// Plain Java Dagger; annotation processors never enter the packaged runtime.

dependencies {
    add("implementation", "com.google.dagger:dagger")
    add("annotationProcessor", "com.google.dagger:dagger-compiler")
}
