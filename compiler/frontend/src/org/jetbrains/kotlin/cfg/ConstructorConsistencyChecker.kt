/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.cfg

import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.cfg.pseudocode.Pseudocode
import org.jetbrains.kotlin.cfg.pseudocode.instructions.KtElementInstruction
import org.jetbrains.kotlin.cfg.pseudocode.instructions.eval.MagicInstruction
import org.jetbrains.kotlin.cfg.pseudocode.instructions.eval.MagicKind
import org.jetbrains.kotlin.cfg.pseudocode.instructions.eval.ReadValueInstruction
import org.jetbrains.kotlin.cfg.pseudocodeTraverser.TraversalOrder
import org.jetbrains.kotlin.cfg.pseudocodeTraverser.traverse
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.types.expressions.OperatorConventions

sealed class LeakingThisDescriptor(val classOrObject: KtClassOrObject) {
    class PropertyIsNull(val property: PropertyDescriptor, classOrObject: KtClassOrObject) : LeakingThisDescriptor(classOrObject)
}

class ConstructorConsistencyChecker private constructor(
        private val classOrObject: KtClassOrObject,
        private val classDescriptor: ClassDescriptor,
        private val trace: BindingTrace,
        private val pseudocode: Pseudocode,
        private val variablesData: PseudocodeVariablesData
) {

    private fun safeThisUsage(expression: KtThisExpression): Boolean {
        val referenceDescriptor = trace.get(BindingContext.REFERENCE_TARGET, expression.instanceReference)
        if (referenceDescriptor != classDescriptor) return true
        val parent = expression.parent
        return when (parent) {
            is KtQualifiedExpression -> parent.selectorExpression is KtSimpleNameExpression
            is KtBinaryExpression -> OperatorConventions.IDENTITY_EQUALS_OPERATIONS.contains(parent.operationToken)
            else -> false
        }
    }

    private fun safeCallUsage(expression: KtCallExpression): Boolean {
        val callee = expression.calleeExpression
        if (callee is KtReferenceExpression) {
            val descriptor = trace.get(BindingContext.REFERENCE_TARGET, callee)
            if (descriptor is FunctionDescriptor) {
                val containingDescriptor = descriptor.containingDeclaration
                if (containingDescriptor != classDescriptor) return true
            }
        }
        return false
    }

    fun check() {
        // List of properties to initialize
        val propertyDescriptors = variablesData.getDeclaredVariables(pseudocode, false)
                .filterIsInstance<PropertyDescriptor>()
                .filter { trace.get(BindingContext.BACKING_FIELD_REQUIRED, it) == true }
        pseudocode.traverse(
                TraversalOrder.FORWARD, variablesData.variableInitializers, { instruction, enterData, exitData ->

            fun firstUninitializedNotNullProperty() = propertyDescriptors.firstOrNull {
                !it.type.isMarkedNullable && !KotlinBuiltIns.isPrimitiveType(it.type) &&
                !it.isLateInit && !(enterData[it]?.definitelyInitialized() ?: false)
            }

            fun handleLeakingThis(expression: KtExpression) {
                val uninitializedProperty = firstUninitializedNotNullProperty()
                if (uninitializedProperty != null) {
                    trace.record(BindingContext.LEAKING_THIS, target(expression),
                                 LeakingThisDescriptor.PropertyIsNull(uninitializedProperty, classOrObject))
                }
            }

            if (instruction.owner != pseudocode) {
                return@traverse
            }

            if (instruction is KtElementInstruction) {
                val element = instruction.element
                when (instruction) {
                    is ReadValueInstruction ->
                        if (element is KtThisExpression) {
                            if (!safeThisUsage(element)) {
                                handleLeakingThis(element)
                            }
                        }
                    is MagicInstruction ->
                        if (instruction.kind == MagicKind.IMPLICIT_RECEIVER && element is KtCallExpression) {
                            if (!safeCallUsage(element)) {
                                handleLeakingThis(element)
                            }
                        }
                }
            }
        })
    }

    companion object {

        @JvmStatic
        fun check(
                constructor: KtSecondaryConstructor,
                trace: BindingTrace,
                pseudocode: Pseudocode,
                pseudocodeVariablesData: PseudocodeVariablesData
        ) = check(constructor.getContainingClassOrObject(), trace, pseudocode, pseudocodeVariablesData)

        @JvmStatic
        fun check(
                classOrObject: KtClassOrObject,
                trace: BindingTrace,
                pseudocode: Pseudocode,
                pseudocodeVariablesData: PseudocodeVariablesData
        ) {
            val classDescriptor = trace.get(BindingContext.CLASS, classOrObject) ?: return
            ConstructorConsistencyChecker(classOrObject, classDescriptor, trace, pseudocode, pseudocodeVariablesData).check()
        }

        private fun target(expression: KtExpression): KtExpression = when (expression) {
            is KtThisExpression -> {
                val selectorOrThis = (expression.parent as? KtQualifiedExpression)?.let {
                    if (it.receiverExpression === expression) it.selectorExpression else null
                } ?: expression
                if (selectorOrThis === expression) selectorOrThis else target(selectorOrThis)
            }
            is KtCallExpression -> expression.let { it.calleeExpression ?: it }
            else -> expression
        }
    }
}