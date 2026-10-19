package com.pzlauncher.zabric.processor.glue;

import com.pzlauncher.zabric.processor.glue.HookDecls.Member;

/** What the parts of one {@link HookAnalyzer} run share: the front end's types and whether anything was reported. */
final class Analysis<T> {
    final TypeSystem<T> types;
    private final Reporter reporter;
    private boolean failed;

    Analysis(TypeSystem<T> types, Reporter reporter) {
        this.types = types;
        this.reporter = reporter;
    }

    void reset() {
        failed = false;
    }

    boolean failed() {
        return failed;
    }

    void error(Object node, String message) {
        failed = true;
        reporter.error(node, message);
    }

    String descriptor(Member<T> member) {
        StringBuilder out = new StringBuilder("(");
        member.params().forEach(p -> out.append(types.descriptor(p)));
        return out.append(')').append(types.descriptor(member.returnType())).toString();
    }

    boolean isVoid(T type) {
        return types.erasedName(type).equals("void");
    }
}
