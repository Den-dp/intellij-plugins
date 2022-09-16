// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.vuejs.web.symbols

import com.intellij.javascript.web._jsType
import com.intellij.javascript.web.symbols.*
import com.intellij.javascript.web.symbols.impl.merge
import com.intellij.lang.documentation.DocumentationTarget
import com.intellij.lang.javascript.psi.JSType
import com.intellij.model.Pointer
import com.intellij.navigation.TargetPresentation
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.containers.Stack
import org.jetbrains.vuejs.codeInsight.toAsset
import javax.swing.Icon

class VueWebTypesMergedSymbol(sourceSymbol: PsiSourcedWebSymbol,
                              webTypesSymbols: Collection<WebSymbol>)
  : PsiSourcedWebSymbolDelegate<PsiSourcedWebSymbol>(sourceSymbol) {

  private val symbols: List<WebSymbol> = sequenceOf(sourceSymbol)
    .plus(webTypesSymbols).toList()

  private val originalName: String?
    get() = symbols.getOrNull(1)
      ?.name
      ?.takeIf { toAsset(it) != toAsset(name) }

  override val origin: WebSymbolsContainer.Origin
    get() = symbols.getOrNull(1)?.origin ?: super.origin

  override fun getModificationCount(): Long =
    symbols.sumOf { it.modificationCount }

  override val nameSegments: List<WebSymbol.NameSegment>
    get() = listOf(WebSymbol.NameSegment(
      0, matchedName.length, symbols
    ))

  override val description: String?
    get() = symbols.firstNotNullOfOrNull { it.description }

  override val descriptionSections: Map<String, String>
    get() = symbols.asSequence()
      .flatMap { it.descriptionSections.entries }
      .distinctBy { it.key }
      .associateBy({ it.key }, { it.value })

  override val docUrl: String?
    get() = symbols.firstNotNullOfOrNull { it.docUrl }

  override val icon: Icon?
    get() = symbols.firstNotNullOfOrNull { it.icon }

  override val deprecated: Boolean
    get() = symbols.any { it.deprecated }

  override val experimental: Boolean
    get() = symbols.any { it.experimental }

  override val required: Boolean?
    get() = symbols.firstNotNullOfOrNull { it.required }

  override val defaultValue: String?
    get() = symbols.firstNotNullOfOrNull { it.defaultValue }

  override val priority: WebSymbol.Priority?
    get() = symbols.asSequence().mapNotNull { it.priority }.maxOrNull()

  override val proximity: Int?
    get() = symbols.asSequence().mapNotNull { it.proximity }.maxOrNull()

  override val jsType: JSType?
    get() = symbols.firstNotNullOfOrNull { it._jsType }

  override val attributeValue: WebSymbol.AttributeValue?
    get() = symbols.asSequence().map { it.attributeValue }.merge()

  override val properties: Map<String, Any>
    get() = symbols.asSequence()
      .flatMap { it.properties.entries }
      .distinctBy { it.key }
      .associateBy({ it.key }, { it.value })

  override val contextContainers: Sequence<WebSymbolsContainer>
    get() = sequenceOf(this)

  override val documentation: WebSymbolDocumentation
    get() = WebSymbolDocumentation.create(this).let { doc ->
      originalName
        ?.let { doc.withDefinition(StringUtil.escapeXmlEntities(it) + " as " + doc.definition) }
      ?: doc
    }

  override fun getDocumentationTarget(): DocumentationTarget =
    object : WebSymbolDocumentationTarget(this) {
      override val presentation: TargetPresentation
        get() = TargetPresentation.builder(originalName ?: name)
          .icon(symbol.icon)
          .presentation()
    }

  override fun getSymbols(namespace: WebSymbolsContainer.Namespace?,
                          kind: SymbolKind,
                          name: String?,
                          params: WebSymbolsNameMatchQueryParams,
                          context: Stack<WebSymbolsContainer>): List<WebSymbolsContainer> =
    symbols
      .flatMap {
        it.getSymbols(namespace, kind, name, params, context)
      }
      .takeIf { it.isNotEmpty() }
      ?.let { list ->
        val containers = mutableListOf<WebSymbolsContainer>()
        var psiSourcedWebSymbol: PsiSourcedWebSymbol? = null
        val webSymbols = mutableListOf<WebSymbol>()
        for (item in list) {
          when (item) {
            is PsiSourcedWebSymbol -> {
              if (psiSourcedWebSymbol == null) {
                psiSourcedWebSymbol = item
              }
              else webSymbols.add(item)
            }
            is WebSymbol -> webSymbols.add(item)
            else -> containers.add(item)
          }
        }
        if (psiSourcedWebSymbol != null) {
          containers.add(VueWebTypesMergedSymbol(psiSourcedWebSymbol, webSymbols))
        }
        else {
          containers.addAll(webSymbols)
        }
        containers
      }
    ?: emptyList()

  override fun getCodeCompletions(namespace: WebSymbolsContainer.Namespace?,
                                  kind: SymbolKind,
                                  name: String?,
                                  params: WebSymbolsCodeCompletionQueryParams,
                                  context: Stack<WebSymbolsContainer>): List<WebSymbolCodeCompletionItem> =
    symbols.asSequence()
      .flatMap { it.getCodeCompletions(namespace, kind, name, params, context) }
      .groupBy { it.name }
      .values
      .map { items ->
        if (items.size == 1)
          items[0]
        else {
          var psiSourcedWebSymbol: PsiSourcedWebSymbol? = null
          val symbols = mutableListOf<WebSymbol>()
          items.asSequence().mapNotNull { it.symbol }.forEach {
            if (it is PsiSourcedWebSymbol && psiSourcedWebSymbol == null)
              psiSourcedWebSymbol = it
            else symbols.add(it)
          }
          psiSourcedWebSymbol?.let {
            items[0].withSymbol(VueWebTypesMergedSymbol(it, symbols))
          } ?: items[0]
        }
      }

  override fun createPointer(): Pointer<out WebSymbol> {
    val pointers = symbols.map { it.createPointer() }
    return Pointer {
      val symbols = pointers.map { it.dereference() ?: return@Pointer null }
      VueWebTypesMergedSymbol(symbols[0] as? PsiSourcedWebSymbol ?: return@Pointer null,
                              symbols.subList(1, symbols.size))
    }
  }


}