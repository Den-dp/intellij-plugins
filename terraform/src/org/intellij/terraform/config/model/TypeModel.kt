// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.intellij.terraform.config.model

import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.childrenOfType
import org.intellij.terraform.config.Constants
import org.intellij.terraform.config.Constants.HCL_DATASOURCE_IDENTIFIER
import org.intellij.terraform.config.Constants.HCL_RESOURCE_IDENTIFIER
import org.intellij.terraform.config.TerraformFileType
import org.intellij.terraform.config.model.local.LocalProviderNamesService
import org.intellij.terraform.config.patterns.TerraformPatterns
import org.intellij.terraform.config.patterns.TerraformPatterns.RequiredProvidersData
import org.intellij.terraform.config.patterns.TerraformPatterns.RequiredProvidersSource
import org.intellij.terraform.hcl.psi.HCLBlock
import org.intellij.terraform.hcl.psi.HCLProperty
import java.io.DataInput
import java.io.DataOutput
import java.io.IOException

enum class ProviderTier(val label: String) {
  TIER_BUILTIN("builtin"),
  TIER_LOCAL("local"),
  TIER_OFFICIAL("official"),
  TIER_PARTNER("partner"),
  TIER_COMMUNITY("community"),
  TIER_NONE("none");

  companion object {
    fun findByLabel(label: String): ProviderTier? {
      return entries.find { it.label == label }
    }
  }
}

class TypeModel(
  resources: List<ResourceType> = emptyList(),
  dataSources: List<DataSourceType> = emptyList(),
  providers: List<ProviderType> = emptyList(),
  provisioners: List<ProvisionerType> = emptyList(),
  backends: List<BackendType> = emptyList(),
  functions: List<Function> = emptyList(),
) {

  val provisioners: List<ProvisionerType> = provisioners.sortedBy { it.type }
  val backends: List<BackendType> = backends.sortedBy { it.type }
  val functions: List<Function> = functions.sortedBy { it.name }

  val providersByFullName: Map<String, ProviderType> = providers.associateBy { it.fullName }
  val resourcesByProvider: Map<String, List<ResourceType>> = resources.groupBy { it.provider.fullName }
  val datasourcesByProvider: Map<String, List<DataSourceType>> = dataSources.groupBy { it.provider.fullName }

  private val providerDefaultPrefixes: Map<String, String>

  init {
    fun getDefaultPrefix(provider: List<ResourceOrDataSourceType>?): String? {
      return provider?.mapNotNull { getResourcePrefix(it.type) }?.firstOrNull()
    }
    providerDefaultPrefixes = providersByFullName.mapNotNull { (name, _) ->
      val prefix = getDefaultPrefix(resourcesByProvider[name]) ?: getDefaultPrefix(datasourcesByProvider[name])
      prefix?.let { name to it }
    }.toMap()
  }

  @Suppress("MemberVisibilityCanBePrivate")
  companion object {

    private val VersionProperty = PropertyType("version", Types.String, hint = SimpleHint("VersionRange"), injectionAllowed = false)
    val TerraformRequiredVersion: PropertyType = PropertyType("required_version", Types.String, hint = SimpleHint("VersionRange"),
                                                              injectionAllowed = false)

    val DependsOnProperty: PropertyType = PropertyType("depends_on", Types.Array,
                                                       hint = ReferenceHint("resource.#name", "data_source.#name", "module.#name",
                                                                            "variable.#name"))

    val DescriptionProperty: PropertyType = PropertyType("description", Types.String)
    val SensitiveProperty: PropertyType = PropertyType("sensitive", Types.Boolean)
    val NullableProperty: PropertyType = PropertyType("nullable", Types.Boolean)

    val Atlas: BlockType = BlockType("atlas", 0, properties = listOf(
      PropertyType("name", Types.String, injectionAllowed = false, required = true)).toMap())
    val Module: BlockType = BlockType("module", 1, properties = listOf(
      PropertyType("source", Types.String, hint = SimpleHint("Url"), required = true),
      VersionProperty,
      DependsOnProperty,
      PropertyType("count", Types.Number, conflictsWith = listOf("for_each")),
      PropertyType("for_each", Types.Any, conflictsWith = listOf("count")),
      PropertyType("providers", MapType(Types.String))
    ).toMap())

    val ErrorMessageProperty: PropertyType = PropertyType("error_message", Types.String)
    val ConditionProperty: PropertyType = PropertyType("condition", Types.Boolean, injectionAllowed = false)
    val Variable_Type: PropertyType = PropertyType("type", Types.Any, injectionAllowed = false)
    val Variable_Default: PropertyType = PropertyType("default", Types.Any)
    val Variable_Validation: BlockType = BlockType("validation", 0, properties = listOf(
      ConditionProperty,
      ErrorMessageProperty
    ).toMap())
    val Variable: BlockType = BlockType("variable", 1, properties = listOf<PropertyOrBlockType>(
      Variable_Type,
      Variable_Default,
      Variable_Validation,
      DescriptionProperty,
      SensitiveProperty,
      NullableProperty
    ).toMap())

    val Connection: BlockType = BlockType("connection", 0, properties = listOf(
      PropertyType("type", Types.String,
                   description = "The connection type that should be used. Valid types are \"ssh\" and \"winrm\" This defaults to \"ssh\"."),
      PropertyType("user", Types.String),
      PropertyType("password", Types.String),
      PropertyType("host", Types.String),
      PropertyType("port", Types.Number),
      PropertyType("timeout", Types.String),
      PropertyType("script_path", Types.String)
    ).toMap())
    val ConnectionPropertiesSSH: Map<String, PropertyOrBlockType> = listOf(
      // ssh
      PropertyType("key_file", Types.String, deprecated = "Use 'private_key'"),
      PropertyType("private_key", Types.String),
      PropertyType("agent", Types.Boolean),

      // bastion ssh
      PropertyType("bastion_host", Types.String),
      PropertyType("bastion_port", Types.Number),
      PropertyType("bastion_user", Types.String),
      PropertyType("bastion_password", Types.String),
      PropertyType("bastion_private_key", Types.String),
      PropertyType("bastion_key_file", Types.String, deprecated = "Use 'bastion_private_key'")
    ).toMap()
    val ConnectionPropertiesWinRM: Map<String, PropertyOrBlockType> = listOf(
      // winrm
      PropertyType("https", Types.Boolean),
      PropertyType("insecure", Types.Boolean),
      PropertyType("cacert", Types.String)
    ).toMap()

    val PreconditionBlock: BlockType = BlockType("precondition", 0, properties = listOf(ConditionProperty, ErrorMessageProperty).toMap())
    val PostconditionBlock: BlockType = BlockType("postcondition", 0, properties = listOf(ConditionProperty, ErrorMessageProperty).toMap())

    val ValueProperty: PropertyType = PropertyType("value", Types.Any, required = true)
    val Output: BlockType = BlockType("output", 1, properties = listOf(
      ValueProperty,
      DescriptionProperty,
      DependsOnProperty,
      SensitiveProperty,
      PreconditionBlock
    ).toMap())

    val ResourceLifecycle: BlockType = BlockType("lifecycle", 0,
                                                 description = "Describe to Terraform how to connect to the resource for provisioning", // TODO: Improve description
                                                 properties = listOf(
                                                   PropertyType("create_before_destroy", Types.Boolean),
                                                   PropertyType("prevent_destroy", Types.Boolean),
                                                   PropertyType("ignore_changes", ListType(Types.Any)),
                                                   PropertyType("replace_triggered_by", ListType(Types.Any)),
                                                   PreconditionBlock,
                                                   PostconditionBlock
                                                 ).toMap())
    val AbstractResourceProvisioner: BlockType = BlockType("provisioner", 1, properties = listOf(
      Connection
    ).toMap())

    val AbstractResourceDynamicContent: BlockType = BlockType("content", 0, required = true)
    val ResourceDynamic: BlockType = BlockType("dynamic", 1, properties = listOf<PropertyOrBlockType>(
      PropertyType("for_each", Types.Any, required = true),
      PropertyType("labels", Types.Array),
      PropertyType("iterator", Types.Identifier),
      AbstractResourceDynamicContent
    ).toMap())

    @JvmField
    val AbstractResource: BlockType = BlockType("resource", 2, properties = listOf<PropertyOrBlockType>(
      PropertyType("id", Types.String, injectionAllowed = false, description = "A unique ID for this resource", optional = false,
                   required = false, computed = true),
      PropertyType("count", Types.Number, conflictsWith = listOf("for_each")),
      PropertyType("for_each", Types.Any, conflictsWith = listOf("count")),
      DependsOnProperty,
      PropertyType("provider", Types.String, hint = ReferenceHint("provider.#type", "provider.#alias")),
      ResourceLifecycle,
      ResourceDynamic,
      // Also may have connection? and provisioner+ blocks
      Connection,
      AbstractResourceProvisioner
    ).toMap())

    @JvmField
    val AbstractDataSource: BlockType = BlockType("data", 2, properties = listOf(
      PropertyType("id", Types.String, injectionAllowed = false, description = "A unique ID for this data source", optional = false,
                   required = false, computed = true),
      PropertyType("count", Types.Number, conflictsWith = listOf("for_each")),
      PropertyType("for_each", Types.Any, conflictsWith = listOf("count")),
      DependsOnProperty,
      ResourceLifecycle,
      PropertyType("provider", Types.String, hint = ReferenceHint("provider.#type", "provider.#alias"))
    ).toMap())

    @JvmField
    val AbstractProvider: BlockType = BlockType("provider", 1, required = false, properties = listOf(
      PropertyType("alias", Types.String, injectionAllowed = false),
      VersionProperty
    ).toMap())
    val AbstractBackend: BlockType = BlockType("backend", 1)
    val FromProperty: PropertyType = PropertyType("from", Types.Identifier, required = true)
    val ToProperty: PropertyType = PropertyType("to", Types.Identifier, required = true)
    val Moved: BlockType = BlockType("moved", properties = listOf(FromProperty, ToProperty).toMap())
    val Cloud: BlockType = BlockType("cloud", properties = listOf(
      PropertyType("organization", Types.String),
      BlockType("workspaces", required = true, properties = listOf(
        PropertyType("name", Types.String, conflictsWith = listOf("tags")),
        PropertyType("tags", ListType(Types.String), conflictsWith = listOf("name"))
      ).toMap())
    ).toMap())
    val Terraform: BlockType = BlockType("terraform", properties = listOf<PropertyOrBlockType>(
      TerraformRequiredVersion,
      PropertyType("experiments", Types.Array),
      BlockType("required_providers"),
      Cloud,
      AbstractBackend
    ).toMap())
    val Locals: BlockType = BlockType("locals")
    val Import: BlockType = BlockType("import", properties = listOf(
      PropertyType("id", Types.String, required = true),
      PropertyType("to", Types.Identifier, required = true),
      PropertyType("provider", Types.String, required = false)
    ).toMap())

    val AssertBlock: BlockType = BlockType("assert", 0, required = true,
                                           properties = listOf(ConditionProperty, ErrorMessageProperty).toMap())

    val CheckBlock: BlockType = BlockType("check", 1, properties = listOf(AbstractDataSource, AssertBlock).toMap())

    val RemovedBlock: BlockType = BlockType("removed", 0, properties = listOf(FromProperty, ResourceLifecycle).toMap())

    val RootBlocks: List<BlockType> = listOf(Atlas, Module, Output, Variable, AbstractProvider,
                                             AbstractResource, AbstractDataSource, Terraform,
                                             Locals, Moved, Import, CheckBlock, RemovedBlock)
    val RootBlocksMap: Map<String, BlockType> = RootBlocks.associateBy(BlockType::literal)

    @JvmStatic
    fun getResourcePrefix(identifier: String): String {
      val stringList = identifier.split("_", limit = 2)
      val prefix = if (stringList.size < 2) identifier else stringList[0]
      return prefix
    }

    @JvmStatic
    fun getResourceName(identifier: String): String {
      val stringList = identifier.split("_", limit = 2)
      val id = if (stringList.size < 2) identifier else stringList[1]
      return id
    }

    @JvmStatic
    fun collectProviderLocalNames(psiElement: PsiElement): Map<String, String> {
      val fileTypeManager = FileTypeManager.getInstance()
      val providerNamesService = LocalProviderNamesService.getInstance()
      val gists = getContainingDir(psiElement)?.childrenOfType<PsiFile>()
        ?.filter { file -> fileTypeManager.isFileOfType(file.virtualFile, TerraformFileType) }
        ?.map { providerNamesService.providersNamesGist.getFileData(it) } ?: return emptyMap<String, String>()
      return gists.flatMap { it.entries }.associate { it.key to it.value }
    }

    fun getContainingDir(psiElement: PsiElement?): PsiDirectory? {
      val containingDir = psiElement?.let { getContainingFile(it)?.parent } ?: return null
      return if (containingDir.isDirectory) containingDir else null
    }

    @RequiresReadLock
    fun getTerraformBlock(psiFile: PsiFile?): HCLBlock? {
      val terraformRootBlock = psiFile?.childrenOfType<HCLBlock>()?.firstOrNull { TerraformPatterns.TerraformRootBlock.accepts(it) }
      return terraformRootBlock
    }
  }

  private fun <T> List<T>.findBinary(elt: String, k: (T) -> String): T? =
    findIndexBinary(elt, k).takeIf { it != -1 }?.let { this[it] }

  private fun <T> List<T>.findIndexBinary(elt: String, k: (T) -> String): Int {
    var low = 0
    var high: Int = this.size - 1

    while (low <= high) {
      val mid = (low + high) ushr 1
      val midVal: T = this[mid]
      val cmp: Int = k(midVal).compareTo(elt)
      when {
        cmp < 0 -> low = mid + 1
        cmp > 0 -> high = mid - 1
        else -> return mid
      }
    }
    return -1
  }

  private fun getProviderNameForIdentifier(identifier: String, psiElement: PsiElement? = null): String {
    val localNames = psiElement?.let { collectProviderLocalNames(it) } ?: emptyMap()
    val providerShortName = getResourcePrefix(identifier)
    return localNames[providerShortName]
           ?: Constants.OFFICIAL_PROVIDERS_NAMESPACE.map { "$it/$providerShortName" }.firstOrNull { providersByFullName.containsKey(it) }
           ?: "hashicorp/$providerShortName" //The last resort
  }

  fun getResourceType(name: String, psiElement: PsiElement? = null): ResourceType? =
    lookupType<ResourceType>(name, psiElement, resourcesByProvider)

  fun getDataSourceType(name: String, psiElement: PsiElement? = null): DataSourceType? =
    lookupType<DataSourceType>(name, psiElement, datasourcesByProvider)

  private fun <T : ResourceOrDataSourceType> lookupType(name: String, psiElement: PsiElement?, typesMap: Map<String, List<T>>): T? {
    val providerName = getProviderNameForIdentifier(name, psiElement)
    val resourceId = getResourceName(name)
    val defaultPrefix = providerDefaultPrefixes[providerName]?.takeIf { it != resourceId }
    val typesCollection = typesMap[providerName] ?: return null
    return typesCollection.firstOrNull { it.type == (defaultPrefix?.let { defaultPrefix + "_" + resourceId } ?: resourceId) }
  }

  fun getProviderType(name: String, psiElement: PsiElement? = null): ProviderType? {
    val providerName = getProviderNameForIdentifier(name, psiElement)
    return providersByFullName[providerName]
  }

  fun getProvisionerType(name: String): ProvisionerType? {
    return provisioners.findBinary(name) { it.type }
  }

  fun getBackendType(name: String): BackendType? {
    return backends.findBinary(name) { it.type }
  }

  fun getFunction(name: String): Function? {
    return functions.findBinary(name) { it.name }
  }

  fun getByFQN(fqn: String, psiElement: PsiElement? = null): PropertyOrBlockType? {
    val parts = fqn.split('.')
    if (parts.size < 2) return null
    val second = when (parts[0]) {
                   HCL_RESOURCE_IDENTIFIER -> {
                     getResourceType(parts[1], psiElement)
                   }

                   HCL_DATASOURCE_IDENTIFIER -> {
                     getDataSourceType(parts[1], psiElement)
                   }

                   else -> null
                 } ?: return null
    if (parts.size == 2) return second
    return find(second, parts.subList(2, parts.size))
  }

  private fun find(block: BlockType, parts: List<String>): PropertyOrBlockType? {
    if (parts.isEmpty()) return null
    val pobt = block.properties[parts[0]] ?: return null
    if (pobt is PropertyType) {
      return if (parts.size == 1) pobt else null
    }
    else if (pobt is BlockType) {
      return if (parts.size == 1) pobt else find(pobt, parts.subList(1, parts.size))
    }
    return null
  }

  fun allResources(): Iterable<ResourceType> = resourcesByProvider.values.flatten()
  fun allDatasources(): Iterable<DataSourceType> = datasourcesByProvider.values.flatten()
  fun allProviders(): Iterable<ProviderType> = providersByFullName.values
}

fun Collection<PropertyOrBlockType>.toMap(): Map<String, PropertyOrBlockType> {
  val grouped: Map<String, List<PropertyOrBlockType>> = this.groupBy { it.name }
  val broken = HashSet<String>()
  for ((k, v) in grouped) {
    if (v.size > 1) broken.add(k)
  }
  if (broken.isNotEmpty()) {
    throw IllegalStateException("Grouping clash on keys: $broken")
  }
  return this.associateBy { it.name }
}
