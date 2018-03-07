/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.truffle.js.nodes.control;

import static com.oracle.truffle.js.runtime.builtins.JSAbstractArray.arrayGetArrayType;
import static com.oracle.truffle.js.runtime.builtins.JSAbstractArray.arraySetArrayType;

import javax.script.Bindings;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.NodeChildren;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.ForeignAccess;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.profiles.ConditionProfile;
import com.oracle.truffle.api.profiles.ValueProfile;
import com.oracle.truffle.js.nodes.JavaScriptNode;
import com.oracle.truffle.js.nodes.access.IsArrayNode;
import com.oracle.truffle.js.nodes.access.JSTargetableNode;
import com.oracle.truffle.js.nodes.cast.JSToPropertyKeyNode;
import com.oracle.truffle.js.nodes.cast.ToArrayIndexNode;
import com.oracle.truffle.js.nodes.interop.ExportValueNode;
import com.oracle.truffle.js.runtime.Symbol;
import com.oracle.truffle.js.runtime.array.ScriptArray;
import com.oracle.truffle.js.runtime.builtins.JSString;
import com.oracle.truffle.js.runtime.objects.JSObject;
import com.oracle.truffle.js.runtime.objects.Undefined;
import com.oracle.truffle.js.runtime.truffleinterop.JSInteropUtil;
import com.oracle.truffle.js.runtime.util.JSClassProfile;

/**
 * 11.4.1 The delete Operator ({@code delete object[property]}).
 */
@NodeChildren({@NodeChild(value = "target", type = JavaScriptNode.class), @NodeChild(value = "property", type = JavaScriptNode.class)})
@NodeInfo(shortName = "delete")
@ImportStatic(value = JSInteropUtil.class)
public abstract class DeletePropertyNode extends JSTargetableNode {
    private final boolean strict;

    protected DeletePropertyNode(boolean strict) {
        this.strict = strict;
    }

    public static DeletePropertyNode create(boolean strict) {
        return create(null, null, strict);
    }

    public static DeletePropertyNode createNonStrict() {
        return create(null, null, false);
    }

    public static DeletePropertyNode create(JavaScriptNode object, JavaScriptNode property, boolean strict) {
        return DeletePropertyNodeGen.create(strict, object, property);
    }

    @Override
    public abstract JavaScriptNode getTarget();

    @Override
    public final Object execute(VirtualFrame frame) {
        return executeWithTarget(frame, evaluateTarget(frame));
    }

    @Override
    public final Object evaluateTarget(VirtualFrame frame) {
        return getTarget().execute(frame);
    }

    public abstract boolean executeEvaluated(TruffleObject objectResult, Object propertyResult);

    @Specialization(guards = "isJSType(targetObject)")
    protected final boolean doJSObject(DynamicObject targetObject, Object key,
                    @Cached("createIsFastArray()") IsArrayNode isArrayNode,
                    @Cached("createBinaryProfile()") ConditionProfile arrayProfile,
                    @Cached("create()") ToArrayIndexNode toArrayIndexNode,
                    @Cached("createBinaryProfile()") ConditionProfile arrayIndexProfile,
                    @Cached("createClassProfile()") ValueProfile arrayTypeProfile,
                    @Cached("create()") JSClassProfile jsclassProfile,
                    @Cached("create()") JSToPropertyKeyNode toPropertyKeyNode) {
        final boolean arrayCondition = isArrayNode.execute(targetObject) && !arrayGetArrayType(targetObject).isLengthNotWritable();
        final Object propertyKey;
        if (arrayProfile.profile(arrayCondition)) {
            Object objIndex = toArrayIndexNode.execute(key);

            if (arrayIndexProfile.profile(objIndex instanceof Long)) {
                ScriptArray array = arrayTypeProfile.profile(arrayGetArrayType(targetObject, arrayCondition));
                arraySetArrayType(targetObject, array.deleteElement(targetObject, (long) objIndex, strict, arrayCondition));
                return true; // always succeeds for fast arrays
            } else {
                propertyKey = objIndex;
            }
        } else {
            propertyKey = toPropertyKeyNode.execute(key);
        }
        return JSObject.delete(targetObject, propertyKey, strict, jsclassProfile);
    }

    @SuppressWarnings("unused")
    @Specialization
    protected static boolean doSymbol(Symbol target, Object property) {
        return true;
    }

    @Specialization
    protected static boolean doString(String target, Object property,
                    @Cached("create()") ToArrayIndexNode toArrayIndexNode) {
        Object objIndex = toArrayIndexNode.execute(property);
        if (objIndex instanceof Long) {
            long index = (Long) objIndex;
            return (index < 0) || (target.length() <= index);
        }
        return !JSString.LENGTH.equals(objIndex);
    }

    @TruffleBoundary
    @Specialization(guards = {"isBindings(target)"})
    protected static boolean doBindings(Object target, Object propertyResult) {
        // e.g. TruffleJSBindings, see JDK-8015830.js
        Bindings bindings = (Bindings) target;
        Object result = bindings.remove(propertyResult);
        return result != null;
    }

    @Specialization(guards = {"isForeignObject(target)"})
    protected static boolean doInterop(TruffleObject target, Object property,
                    @Cached("createRemove()") Node removeNode,
                    @Cached("create()") ExportValueNode exportNode) {
        try {
            ForeignAccess.sendRemove(removeNode, target, exportNode.executeWithTarget(property, Undefined.instance));
            return true;
        } catch (UnknownIdentifierException | UnsupportedMessageException e) {
            return false;
        }
    }

    @SuppressWarnings("unused")
    @Specialization(guards = {"!isTruffleObject(target)", "!isString(target)", "!isBindings(target)"})
    public boolean doOther(Object target, Object property) {
        return true;
    }

    abstract JavaScriptNode getProperty();

    @Override
    protected JavaScriptNode copyUninitialized() {
        return create(cloneUninitialized(getTarget()), cloneUninitialized(getProperty()), strict);
    }

    @Override
    public boolean isResultAlwaysOfType(Class<?> clazz) {
        return clazz == boolean.class;
    }
}
