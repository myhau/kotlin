/*
 * Copyright 2010-2016 JetBrains s.r.o.
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

package org.jetbrains.kotlin.idea.caches.resolve

import com.intellij.openapi.module.Module
import com.intellij.openapi.module.impl.scopes.LibraryScopeBase
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.*
import com.intellij.openapi.roots.impl.libraries.LibraryEx
import com.intellij.openapi.roots.libraries.Library
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.util.SmartList
import org.jetbrains.kotlin.analyzer.ModuleInfo
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.utils.alwaysNull
import org.jetbrains.kotlin.utils.emptyOrSingletonList
import java.lang.reflect.Method
import java.util.*

private val LIBRARY_NAME_PREFIX: String = "library "

// TODO used reflection to be compatible with IDEA from both 143 and 144 branches,
// TODO switch to directly using when "since-build" will be >= 144.3357.4
private val getRelatedProductionModule: (Module) -> Module? = run {
    val klass =
            try {
                Class.forName("com.intellij.openapi.roots.TestModuleProperties")
            } catch (e: ClassNotFoundException) {
                return@run alwaysNull()
            }


    val getInstanceMethod: Method
    val getProductionModuleMethod: Method

    try {
        getInstanceMethod = klass.getDeclaredMethod("getInstance", Module::class.java)
        getProductionModuleMethod = klass.getDeclaredMethod("getProductionModule")
    }
    catch (e: NoSuchMethodException) {
        return@run alwaysNull()
    }

    return@run { module ->
        getInstanceMethod(null, module)?.let {
            getProductionModuleMethod(it) as Module?
        }
    }
}

interface IdeaModuleInfo : ModuleInfo {
    fun contentScope(): GlobalSearchScope

    val moduleOrigin: ModuleOrigin

    override val capabilities: Map<ModuleDescriptor.Capability<*>, Any?>
        get() = mapOf(OriginCapability to moduleOrigin)

    override fun dependencies(): List<IdeaModuleInfo>
}

private fun orderEntryToModuleInfo(project: Project, orderEntry: OrderEntry, productionOnly: Boolean): List<IdeaModuleInfo> {
    fun Module.toInfos() = if (productionOnly) listOf(productionSourceInfo()) else listOf(testSourceInfo(), productionSourceInfo())

    if (!orderEntry.isValid) return emptyList()

    return when (orderEntry) {
        is ModuleSourceOrderEntry -> {
            orderEntry.getOwnerModule().toInfos()
        }
        is ModuleOrderEntry -> {
            orderEntry.module?.toInfos().orEmpty()
        }
        is LibraryOrderEntry -> {
            val library = orderEntry.library ?: return listOf()
            emptyOrSingletonList(LibraryInfo(project, library))
        }
        is JdkOrderEntry -> {
            val sdk = orderEntry.jdk ?: return listOf()
            emptyOrSingletonList(SdkInfo(project, sdk))
        }
        else -> {
            throw IllegalStateException("Unexpected order entry $orderEntry")
        }
    }
}

private fun <T> Module.cached(provider: CachedValueProvider<T>): T {
    return CachedValuesManager.getManager(project).getCachedValue(this, provider)
}

private fun ideaModelDependencies(module: Module, productionOnly: Boolean): List<IdeaModuleInfo> {
    //NOTE: lib dependencies can be processed several times during recursive traversal
    val result = LinkedHashSet<IdeaModuleInfo>()
    val dependencyEnumerator = ModuleRootManager.getInstance(module).orderEntries().compileOnly().recursively().exportedOnly()
    if (productionOnly) {
        dependencyEnumerator.productionOnly()
    }
    dependencyEnumerator.forEach {
        orderEntry ->
        result.addAll(orderEntryToModuleInfo(module.project, orderEntry!!, productionOnly))
        true
    }
    return result.toList()
}

interface ModuleSourceInfo : IdeaModuleInfo {
    val module: Module
    override val moduleOrigin: ModuleOrigin
        get() = ModuleOrigin.MODULE
}

data class ModuleProductionSourceInfo(override val module: Module) : ModuleSourceInfo {
    override val name = Name.special("<production sources for module ${module.name}>")

    override fun contentScope(): GlobalSearchScope = ModuleProductionSourceScope(module)

    override fun dependencies() = module.cached(CachedValueProvider {
        CachedValueProvider.Result(
                ideaModelDependencies(module, productionOnly = true),
                ProjectRootModificationTracker.getInstance(module.project))
    })
}

//TODO: (module refactoring) do not create ModuleTestSourceInfo when there are no test roots for module
data class ModuleTestSourceInfo(override val module: Module) : ModuleSourceInfo {
    override val name = Name.special("<test sources for module ${module.name}>")

    override fun contentScope(): GlobalSearchScope = ModuleTestSourceScope(module)

    override fun dependencies() = module.cached(CachedValueProvider {
        CachedValueProvider.Result(
                ideaModelDependencies(module, productionOnly = false),
                ProjectRootModificationTracker.getInstance(module.project))
    })

    override fun modulesWhoseInternalsAreVisible() = module.cached(CachedValueProvider {
        val list = SmartList<ModuleInfo>(module.productionSourceInfo())

        getRelatedProductionModule(module)?.let {
            list.add(it.productionSourceInfo())
        }

        CachedValueProvider.Result(list, ProjectRootModificationTracker.getInstance(module.project))
    })
}

internal fun ModuleSourceInfo.isTests() = this is ModuleTestSourceInfo

fun Module.productionSourceInfo(): ModuleProductionSourceInfo = ModuleProductionSourceInfo(this)
fun Module.testSourceInfo(): ModuleTestSourceInfo = ModuleTestSourceInfo(this)

private abstract class ModuleSourceScope(val module: Module) : GlobalSearchScope(module.project) {
    override fun compare(file1: VirtualFile, file2: VirtualFile) = 0
    override fun isSearchInModuleContent(aModule: Module) = aModule == module
    override fun isSearchInLibraries() = false
}

private class ModuleProductionSourceScope(module: Module) : ModuleSourceScope(module) {
    val moduleFileIndex = ModuleRootManager.getInstance(module).fileIndex

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        return (other is ModuleProductionSourceScope && module == other.module)
    }
    // KT-6206
    override fun hashCode(): Int = 31 * module.hashCode()

    override fun contains(file: VirtualFile) = moduleFileIndex.isInSourceContent(file) && !moduleFileIndex.isInTestSourceContent(file)
}

private class ModuleTestSourceScope(module: Module) : ModuleSourceScope(module) {
    val moduleFileIndex = ModuleRootManager.getInstance(module).fileIndex

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        return (other is ModuleTestSourceScope && module == other.module)
    }
    // KT-6206
    override fun hashCode(): Int = 37 * module.hashCode()

    override fun contains(file: VirtualFile) = moduleFileIndex.isInTestSourceContent(file)
}

open class LibraryInfo(val project: Project, val library: Library) : IdeaModuleInfo {
    override val moduleOrigin: ModuleOrigin
        get() = ModuleOrigin.LIBRARY

    override val name: Name = Name.special("<$LIBRARY_NAME_PREFIX${library.name}>")

    override fun contentScope(): GlobalSearchScope = LibraryWithoutSourceScope(project, library)

    override val isLibrary: Boolean
        get() = true

    override fun dependencies(): List<IdeaModuleInfo> {
        val result = LinkedHashSet<IdeaModuleInfo>()
        result.add(this)

        val (libraries, sdks) = LibraryDependenciesCache(project).getLibrariesAndSdksUsedWith(library)

        sdks.mapTo(result) { SdkInfo(project, it) }
        libraries.filter { it is LibraryEx && !it.isDisposed }.mapTo(result) { LibraryInfo(project, it) }

        return result.toList()
    }

    override fun toString() = "LibraryInfo(libraryName=${library.name})"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        return (other is LibraryInfo && library == other.library)
    }

    override fun hashCode(): Int = 43 * library.hashCode()
}

internal data class LibrarySourceInfo(val project: Project, val library: Library) : IdeaModuleInfo {
    override val moduleOrigin: ModuleOrigin
        get() = ModuleOrigin.OTHER

    override val name: Name = Name.special("<sources for library ${library.name}>")

    override fun contentScope() = GlobalSearchScope.EMPTY_SCOPE

    override val isLibrary: Boolean
        get() = true

    override fun dependencies(): List<IdeaModuleInfo> {
        return listOf(this) + LibraryInfo(project, library).dependencies()
    }

    override fun modulesWhoseInternalsAreVisible(): Collection<ModuleInfo> {
        return listOf(LibraryInfo(project, library))
    }

    override fun toString() = "LibrarySourceInfo(libraryName=${library.name})"
}

//TODO: (module refactoring) there should be separate SdkSourceInfo but there are no kotlin source in existing sdks for now :)
data class SdkInfo(val project: Project, val sdk: Sdk) : IdeaModuleInfo {
    override val moduleOrigin: ModuleOrigin
        get() = ModuleOrigin.LIBRARY

    override val name: Name = Name.special("<$LIBRARY_NAME_PREFIX${sdk.name}>")

    override fun contentScope(): GlobalSearchScope = SdkScope(project, sdk)

    override fun dependencies(): List<IdeaModuleInfo> = listOf(this)
}

internal object NotUnderContentRootModuleInfo : IdeaModuleInfo {
    override val moduleOrigin: ModuleOrigin
        get() = ModuleOrigin.OTHER

    override val name: Name = Name.special("<special module for files not under source root>")

    override fun contentScope() = GlobalSearchScope.EMPTY_SCOPE

    //TODO: (module refactoring) dependency on runtime can be of use here
    override fun dependencies(): List<IdeaModuleInfo> = listOf(this)
}

private class LibraryWithoutSourceScope(project: Project, private val library: Library) :
        LibraryScopeBase(project, library.getFiles(OrderRootType.CLASSES), arrayOf<VirtualFile>()) {

    override fun equals(other: Any?) = other is LibraryWithoutSourceScope && library == other.library

    override fun hashCode() = library.hashCode()
}

//TODO: (module refactoring) android sdk has modified scope
private class SdkScope(project: Project, private val sdk: Sdk) :
        LibraryScopeBase(project, sdk.rootProvider.getFiles(OrderRootType.CLASSES), arrayOf<VirtualFile>()) {

    override fun equals(other: Any?) = other is SdkScope && sdk == other.sdk

    override fun hashCode() = sdk.hashCode()
}

internal fun IdeaModuleInfo.isLibraryClasses() = this is SdkInfo || this is LibraryInfo

val OriginCapability = ModuleDescriptor.Capability<ModuleOrigin>("MODULE_ORIGIN")

enum class ModuleOrigin {
    MODULE,
    LIBRARY,
    OTHER
}
