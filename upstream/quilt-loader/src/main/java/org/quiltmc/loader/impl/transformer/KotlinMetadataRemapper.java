/*
 * Copyright 2025 QuiltMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.quiltmc.loader.impl.transformer;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.Remapper;
import org.quiltmc.loader.impl.QuiltLoaderImpl;
import org.quiltmc.loader.impl.util.log.Log;
import org.quiltmc.loader.impl.util.log.LogCategory;

import net.fabricmc.tinyremapper.api.TrClass;

public class KotlinMetadataRemapper extends ClassVisitor {
	Remapper remapper;

	String currentName;

	protected KotlinMetadataRemapper(TrClass cls, ClassVisitor parent) {
		super(QuiltLoaderImpl.ASM_VERSION, parent);
		this.remapper = cls.getEnvironment().getRemapper();
	}

	@Override
	public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
		super.visit(version, access, name, signature, superName, interfaces);
		currentName = name;
	}

	// This  is based off the observation that the nice, clean, metadata remapper written for Loom in Kotlin
	// only uses remapper.map, mapDesc, and mapFieldDesc.
	// These are extremely identifiable in the metadata's constant pool: field descriptors begin with L,
	// method descriptors begin with (, and classes we need to remap begin with class_ or contain a package, so we can
	// just brute-force the constant pool
	// and remap those 3 things as they come by.
	@Override
	public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
		if (descriptor.equals("Lkotlin/Metadata;")) {
			return new AnnotationVisitor(api, super.visitAnnotation(descriptor, visible)) {
				@Override
				public AnnotationVisitor visitArray(String name) {
					if ("d2".equals(name)) {
						return new AnnotationVisitor(api, super.visitArray(name)) {
							@Override
							public void visit(String name, Object value) {
								if (value instanceof String) {
									value = remapCandidate((String) value);
								}

								super.visit(name, value);
							}
						};
					}
					return super.visitArray(name);
				}
			};
		}
		return super.visitAnnotation(descriptor, visible);
	}

	private String remapCandidate(String candidate) {
		try {
			if (candidate.startsWith("(")) {
				candidate = remapper.mapMethodDesc(candidate);
			} else if (candidate.startsWith("L")) { // this could technically catch strays but it should just not do anything
				candidate = remapper.mapDesc(candidate);
			} else if (candidate.startsWith("class_") || candidate.contains("/")) { // must go last to not accidentally catch descriptors
				candidate = remapper.map(candidate);
			} else {
				// hope nothing goes wrong
			}
		} catch (IllegalArgumentException e) {
			// ASM's remapper currently throws this if the candidate is an invalid method desc / desc / class
			// TODO: Reimplement this in a way that won't generate exceptions!
			Log.warn(LogCategory.CACHE, "Encountered an invalid / unknown Kotlin metdata annotation value '" + candidate + "' in " + currentName, e);
		} catch (Throwable t) {
			String msg = "While processing a kotlin metadata annotation value '" + candidate + "' in " + currentName;
			t.addSuppressed(new Throwable(msg));
			throw t;
		}
		return candidate;
	}
}
