/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.truffle.js.test.instrumentation;

import org.junit.Test;

import com.oracle.truffle.js.nodes.instrumentation.JSTags.ControlFlowBlockTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.ControlFlowBranchTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.ControlFlowRootTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.LiteralExpressionTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.WritePropertyExpressionTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.WriteVariableExpressionTag;

public class IfStatementTest extends FineGrainedAccessTest {

    @Test
    public void basicNoBranch() {
        evalAllTags("if (!true) {};");

        // JS will write the result to <return>
        enter(WriteVariableExpressionTag.class, (e, write) -> {
            enter(ControlFlowRootTag.class, (e1, ifbody) -> {
                assertAttribute(e1, TYPE, ControlFlowRootTag.Type.Conditional.name());
                // condition
                enter(ControlFlowBranchTag.class, (e2, ifstatement) -> {
                    assertAttribute(e2, TYPE, ControlFlowBranchTag.Type.Condition.name());

                    enter(LiteralExpressionTag.class).exit();
                    ifstatement.input(false);
                }).exit(assertReturnValue(false));
                ifbody.input(false);
                // no branch is executed; body returns
            }).exit();
        }).exit();
    }

    @Test
    public void basicBranch() {
        evalAllTags("if (true) { 3; };");

        // JS will write the result to <return>
        enter(WriteVariableExpressionTag.class, (e, write) -> {
            enter(ControlFlowRootTag.class, (e1, ifbody) -> {
                assertAttribute(e1, TYPE, ControlFlowRootTag.Type.Conditional.name());
                // condition
                enter(ControlFlowBranchTag.class, (e2, ifstatement) -> {
                    assertAttribute(e2, TYPE, ControlFlowBranchTag.Type.Condition.name());

                    enter(LiteralExpressionTag.class).exit();
                    ifstatement.input(true);
                }).exit(assertReturnValue(true));
                ifbody.input(true);
                // enter if branch
                enter(ControlFlowBlockTag.class, (e2, b) -> {
                    enter(WriteVariableExpressionTag.class, (e3, v) -> {
                        enter(LiteralExpressionTag.class).exit(assertReturnValue(3));
                        v.input(3);
                    }).exit();
                    b.input(3);
                }).exit();
                ifbody.input(3);
            }).exit();
        }).exit();
    }

    @Test
    public void basicFilter() {
        String src = "if (true) { 3; };";

        evalWithTags(src, new Class[]{ControlFlowRootTag.class, ControlFlowBlockTag.class, ControlFlowBranchTag.class},
                        new Class[]{});

        enter(ControlFlowRootTag.class, (e1) -> {
            assertAttribute(e1, TYPE, ControlFlowRootTag.Type.Conditional.name());
            // condition
            enter(ControlFlowBranchTag.class, (e) -> {
                assertAttribute(e, TYPE, ControlFlowBranchTag.Type.Condition.name());
            }).exit(assertReturnValue(true));
            // enter if branch
            enter(ControlFlowBlockTag.class).exit();
        }).exit();
    }

    @Test
    public void writeWithTernary() {
        String src = "var a = {x:0}; a.x = 100 > 0 ? 1 : 0;";
        evalWithTag(src, WritePropertyExpressionTag.class);

        enter(WritePropertyExpressionTag.class, (e1, pw1) -> {
            assertAttribute(e1, KEY, "a");
            pw1.input(assertGlobalObjectInput);
            pw1.input(assertJSObjectInput);
        }).exit();
        enter(WritePropertyExpressionTag.class, (e1, pw1) -> {
            assertAttribute(e1, KEY, "x");
            pw1.input(assertJSObjectInput);
            pw1.input(1);
        }).exit();
    }

}