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

package org.jetbrains.kotlin.serialization.jvm

import org.jetbrains.kotlin.load.kotlin.JvmNameResolver
import org.jetbrains.kotlin.protobuf.ExtensionRegistryLite
import org.jetbrains.kotlin.serialization.ClassData
import org.jetbrains.kotlin.serialization.PackageData
import org.jetbrains.kotlin.serialization.ProtoBuf
import org.jetbrains.kotlin.serialization.deserialization.*
import java.io.ByteArrayInputStream
import java.io.InputStream

object JvmProtoBufUtil {
    val EXTENSION_REGISTRY: ExtensionRegistryLite = ExtensionRegistryLite.newInstance().apply(JvmProtoBuf::registerAllExtensions)

    @JvmStatic
    fun readClassDataFrom(data: Array<String>, strings: Array<String>): ClassData =
        readClassDataFrom(BitEncoding.decodeBytes(data), strings)

    @JvmStatic
    fun readClassDataFrom(bytes: ByteArray, strings: Array<String>): ClassData {
        val input = ByteArrayInputStream(bytes)
        return ClassData(input.readNameResolver(strings), ProtoBuf.Class.parseFrom(input, EXTENSION_REGISTRY))
    }

    @JvmStatic
    fun readPackageDataFrom(data: Array<String>, strings: Array<String>): PackageData =
        readPackageDataFrom(BitEncoding.decodeBytes(data), strings)

    @JvmStatic
    fun readPackageDataFrom(bytes: ByteArray, strings: Array<String>): PackageData {
        val input = ByteArrayInputStream(bytes)
        return PackageData(input.readNameResolver(strings), ProtoBuf.Package.parseFrom(input, EXTENSION_REGISTRY))
    }

    @JvmStatic
    fun readFunctionDataFrom(data: Array<String>, strings: Array<String>): Pair<JvmNameResolver, ProtoBuf.Function> {
        val input = ByteArrayInputStream(BitEncoding.decodeBytes(data))
        return Pair(input.readNameResolver(strings), ProtoBuf.Function.parseFrom(input, EXTENSION_REGISTRY))
    }

    private fun InputStream.readNameResolver(strings: Array<String>): JvmNameResolver =
        JvmNameResolver(JvmProtoBuf.StringTableTypes.parseDelimitedFrom(this, EXTENSION_REGISTRY), strings)

    // returns JVM signature in the format: "equals(Ljava/lang/Object;)Z"
    fun getJvmMethodSignature(
            proto: ProtoBuf.Function,
            nameResolver: NameResolver,
            typeTable: TypeTable
    ): String? {
        val signature = proto.getExtensionOrNull(JvmProtoBuf.methodSignature)
        val name = if (signature != null && signature.hasName()) signature.name else proto.name
        val desc = if (signature != null && signature.hasDesc()) {
            nameResolver.getString(signature.desc)
        }
        else {
            val parameterTypes = listOfNotNull(proto.receiverType(typeTable)) + proto.valueParameterList.map { it.type(typeTable) }

            val parametersDesc = parameterTypes.map { mapTypeDefault(it, nameResolver) ?: return null }
            val returnTypeDesc = mapTypeDefault(proto.returnType(typeTable), nameResolver) ?: return null

            parametersDesc.joinToString(separator = "", prefix = "(", postfix = ")") + returnTypeDesc
        }
        return nameResolver.getString(name) + desc
    }

    fun getJvmConstructorSignature(
            proto: ProtoBuf.Constructor,
            nameResolver: NameResolver,
            typeTable: TypeTable
    ): String? {
        val signature = proto.getExtensionOrNull(JvmProtoBuf.constructorSignature)
        val desc = if (signature != null && signature.hasDesc()) {
            nameResolver.getString(signature.desc)
        }
        else {
            proto.valueParameterList.map {
                mapTypeDefault(it.type(typeTable), nameResolver) ?: return null
            }.joinToString(separator = "", prefix = "(", postfix = ")V")
        }
        return "<init>" + desc
    }

    fun getJvmFieldSignature(
            proto: ProtoBuf.Property,
            nameResolver: NameResolver,
            typeTable: TypeTable
    ): PropertySignature? {
        val signature = proto.getExtensionOrNull(JvmProtoBuf.propertySignature) ?: return null
        val field =
                if (signature.hasField()) signature.field else null

        val name = if (field != null && field.hasName()) field.name else proto.name
        val desc =
                if (field != null && field.hasDesc()) nameResolver.getString(field.desc)
                else mapTypeDefault(proto.returnType(typeTable), nameResolver) ?: return null

        return PropertySignature(nameResolver.getString(name), desc)
    }

    data class PropertySignature(val name: String, val desc: String)

    private fun mapTypeDefault(type: ProtoBuf.Type, nameResolver: NameResolver): String? {
        return if (type.hasClassName()) ClassMapperLite.mapClass(nameResolver.getClassId(type.className)) else null
    }
}
