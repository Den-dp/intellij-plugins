// This is a generated file. Not intended for manual editing.
package org.intellij.prisma.lang.psi;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.psi.PsiElement;
import com.intellij.psi.StubBasedPsiElement;
import org.intellij.prisma.lang.psi.stubs.PrismaNamedStub;

public interface PrismaEnumValueDeclaration extends StubBasedPsiElement<PrismaNamedStub<PrismaEnumValueDeclaration>>, PrismaMemberDeclaration, PrismaFieldAttributeOwner {

  @NotNull
  List<PrismaFieldAttribute> getFieldAttributeList();

  @NotNull
  PsiElement getIdentifier();

}
