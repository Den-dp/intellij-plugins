// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.angular2.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.lang.javascript.presentable.JSFormatUtil
import com.intellij.lang.javascript.presentable.JSNamedElementPresenter
import com.intellij.lang.javascript.psi.JSElement
import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.JSThisExpression
import com.intellij.lang.javascript.psi.ecma6.TypeScriptClass
import com.intellij.lang.javascript.psi.ecmal4.JSAttributeList
import com.intellij.lang.javascript.psi.ecmal4.JSAttributeList.AccessType
import com.intellij.lang.javascript.psi.ecmal4.JSAttributeListOwner
import com.intellij.lang.javascript.refactoring.JSVisibilityUtil.getPresentableAccessModifier
import com.intellij.openapi.util.text.StringUtil.capitalize
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.parentOfType
import com.intellij.util.asSafely
import com.intellij.webSymbols.PsiSourcedWebSymbol
import com.intellij.webSymbols.utils.unwrapMatchedSymbols
import org.angular2.codeInsight.attributes.Angular2AttributeDescriptor
import org.angular2.codeInsight.config.isStrictTemplates
import org.angular2.entities.Angular2ComponentLocator
import org.angular2.inspections.quickfixes.AngularChangeModifierQuickFix
import org.angular2.lang.Angular2Bundle
import org.angular2.lang.expr.Angular2Language
import org.angular2.lang.expr.psi.Angular2ElementVisitor
import org.angular2.lang.html.Angular2HtmlLanguage
import org.angular2.lang.html.psi.Angular2HtmlPropertyBinding
import org.angular2.lang.html.psi.PropertyBindingType
import org.angular2.web.Angular2WebSymbolsQueryConfigurator

class AngularInaccessibleSymbolInspection : LocalInspectionTool() {

  override fun buildVisitor(holder: ProblemsHolder,
                            isOnTheFly: Boolean,
                            session: LocalInspectionToolSession): PsiElementVisitor {
    val fileLang = holder.file.language
    if (fileLang.isKindOf(Angular2HtmlLanguage.INSTANCE) || Angular2Language.INSTANCE.`is`(fileLang)) {
      return object : Angular2ElementVisitor() {

        override fun visitElement(element: PsiElement) {
          when (element) {
            is JSReferenceExpression -> checkReference(element)
            is Angular2HtmlPropertyBinding -> checkPropertyBinding(element)
          }
          super.visitElement(element)
        }

        private fun checkReference(node: JSReferenceExpression) {
          if (node.qualifier == null || node.qualifier is JSThisExpression) {
            val resolved = node.resolve()
            val clazz = PsiTreeUtil.getContextOfType(resolved, TypeScriptClass::class.java)
            if (clazz != null && resolved is JSElement && !isAccessible(resolved, AccessType.PROTECTED)) {
              holder.registerProblem(
                node.referenceNameElement ?: node,
                capitalize(Angular2Bundle.message(
                  if (isStrictTemplates(clazz))
                    "angular.inspection.inaccessible-symbol.strict.private.message"
                  else
                    "angular.inspection.inaccessible-symbol.aot.message",
                  getKind(resolved), getName(resolved), getAccessModifier(resolved), clazz.name ?: "<unknown>")
                ),
                AngularChangeModifierQuickFix(AccessType.PROTECTED))
            }
          }
        }

        private fun checkPropertyBinding(element: Angular2HtmlPropertyBinding) {
          if (element.bindingType == PropertyBindingType.PROPERTY) {
            val inputElements = getInputSourceElements(element)
            val owner = Angular2ComponentLocator.findComponentClass(element)
                        ?: return
            for (input in inputElements) {
              val accessType = input.attributeList?.accessType
              val inputOwner = input.parentOfType<TypeScriptClass>() ?: return
              val minAccessType = if (inputOwner == owner) AccessType.PROTECTED else AccessType.PUBLIC
              if (!isAccessible(input, minAccessType)) {
                if (!isStrictTemplates(element)) return
                holder.registerProblem(
                  element.nameElement,
                  capitalize(Angular2Bundle.message(
                    if (accessType == AccessType.PRIVATE)
                      "angular.inspection.inaccessible-symbol.strict.private.message"
                    else
                      "angular.inspection.inaccessible-symbol.strict.protected.message",
                    getKind(input), getName(input), getAccessModifier(input), inputOwner.name ?: "<unknown>")
                  ),
                  AngularChangeModifierQuickFix(minAccessType, inputOwner.name))
              }
              else if (input.attributeList?.hasModifier(JSAttributeList.ModifierType.READONLY) == true) {
                holder.registerProblem(
                  element.nameElement,
                  capitalize(Angular2Bundle.message(
                    "angular.inspection.inaccessible-symbol.strict.read-only.message",
                    getKind(input), getName(input), getAccessModifier(input), inputOwner.name ?: "<unknown>")
                  ))
              }
            }
          }
        }
      }
    }
    return PsiElementVisitor.EMPTY_VISITOR
  }

}

fun getInputSourceElements(element: Angular2HtmlPropertyBinding): List<JSAttributeListOwner> =
  element.descriptor?.asSafely<Angular2AttributeDescriptor>()?.symbol
    ?.unwrapMatchedSymbols()
    ?.filter { it.kind == Angular2WebSymbolsQueryConfigurator.KIND_NG_DIRECTIVE_INPUTS }
    ?.filterIsInstance<PsiSourcedWebSymbol>()
    ?.mapNotNull { it.source }
    ?.filterIsInstance<JSAttributeListOwner>()
    ?.toList()
  ?: emptyList()

fun isAccessible(member: PsiElement?, minAccessType: AccessType): Boolean {
  if (member is JSAttributeListOwner && !(member is JSFunction && member.isConstructor)) {
    val attributes = member.attributeList ?: return true
    val accessType = attributes.accessType
    return attributes.hasModifier(JSAttributeList.ModifierType.STATIC)
           || minAccessType.level <= accessType.level
  }
  return true
}

private val AccessType.level
  get() = when (this) {
    AccessType.PACKAGE_LOCAL -> 1
    AccessType.PUBLIC -> 2
    AccessType.PRIVATE -> 0
    AccessType.PROTECTED -> 1
  }

private fun getKind(member: PsiElement): String {
  return JSNamedElementPresenter(member).describeElementKind()
}

private fun getAccessModifier(member: JSElement): String {
  return getPresentableAccessModifier(member)?.text ?: ""
}

private fun getName(member: PsiElement): String {
  return member.asSafely<PsiNamedElement>()?.name
         ?: JSFormatUtil.getAnonymousElementPresentation()
}