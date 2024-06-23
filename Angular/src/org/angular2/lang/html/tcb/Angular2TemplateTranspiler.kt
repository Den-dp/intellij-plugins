package org.angular2.lang.html.tcb

import com.intellij.psi.PsiFile
import org.angular2.codeInsight.config.Angular2Compiler
import org.angular2.entities.Angular2ClassBasedComponent
import org.angular2.entities.Angular2Component
import org.angular2.lang.html.Angular2HtmlFile

object Angular2TemplateTranspiler {

  internal fun createFileContext(file: PsiFile): Environment {
    return Environment(Angular2Compiler.getTypeCheckingConfig(file), file)
  }

  internal fun transpileTemplate(
    fileContext: FileContext,
    component: Angular2Component,
    tcbId: String,
  ): TranspiledTemplate? {
    val boundTarget = BoundTarget(component)
    if (boundTarget.templateFile == null) return null

    val context = Context(
      fileContext as Environment,
      OutOfBandDiagnosticRecorder(), tcbId,
      boundTarget,
    )

    val scope = Scope.forNodes(context, null, null, boundTarget.templateRoots, null)
    val statements = scope.render()

    return Expression {
      append("function _tcb${context.id}")

      val cls = (component as? Angular2ClassBasedComponent)?.typeScriptClass
      if (cls != null) {
        val typeParameters = cls.typeParameters
        if (typeParameters.isNotEmpty()) {
          append("<")
          typeParameters.forEachIndexed { i, param ->
            if (i > 0) {
              append(", ")
            }
            append(param.text)
          }
          append(">")
        }
        append("(this: ").append(cls.name ?: "never")
        if (typeParameters.isNotEmpty()) {
          append("<")
          typeParameters.forEachIndexed { i, param ->
            if (i > 0) {
              append(", ")
            }
            append(param.name ?: "never")
          }
          append(">")
        }
        append(") ")
      }
      else {
        append("() ")
      }
      codeBlock {
        for (it in statements) {
          appendStatement(it)
        }
      }
    }.asTranspiledTemplate(boundTarget.templateFile)
  }

  interface FileContext {
    fun getCommonCode(): String
  }

  interface TranspiledTemplate {
    val templateFile: Angular2HtmlFile
    val generatedCode: String
    val sourceMappings: List<SourceMapping>
  }

  interface SourceMapping {
    val sourceOffset: Int
    val sourceLength: Int
    val generatedOffset: Int
    val generatedLength: Int
    val ignoreDiagnostics: Boolean

    fun offsetBy(generatedOffset: Int = 0, sourceOffset: Int = 0): SourceMapping

  }
}