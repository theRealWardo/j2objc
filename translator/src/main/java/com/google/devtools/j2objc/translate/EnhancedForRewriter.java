/*
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

package com.google.devtools.j2objc.translate;

import com.google.devtools.j2objc.ast.Block;
import com.google.devtools.j2objc.ast.EnhancedForStatement;
import com.google.devtools.j2objc.ast.Expression;
import com.google.devtools.j2objc.ast.FieldAccess;
import com.google.devtools.j2objc.ast.InfixExpression;
import com.google.devtools.j2objc.ast.LabeledStatement;
import com.google.devtools.j2objc.ast.MethodInvocation;
import com.google.devtools.j2objc.ast.PostfixExpression;
import com.google.devtools.j2objc.ast.PrefixExpression;
import com.google.devtools.j2objc.ast.SimpleName;
import com.google.devtools.j2objc.ast.SingleVariableDeclaration;
import com.google.devtools.j2objc.ast.Statement;
import com.google.devtools.j2objc.ast.TreeUtil;
import com.google.devtools.j2objc.ast.TreeVisitor;
import com.google.devtools.j2objc.ast.VariableDeclarationStatement;
import com.google.devtools.j2objc.ast.WhileStatement;
import com.google.devtools.j2objc.jdt.BindingConverter;
import com.google.devtools.j2objc.types.GeneratedVariableBinding;
import com.google.devtools.j2objc.types.PointerTypeBinding;
import com.google.devtools.j2objc.util.BindingUtil;
import com.google.j2objc.annotations.AutoreleasePool;
import com.google.j2objc.annotations.LoopTranslation;
import com.google.j2objc.annotations.LoopTranslation.LoopStyle;

import org.eclipse.jdt.core.dom.IAnnotationBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.Modifier;

import java.util.List;
import javax.lang.model.element.VariableElement;

/**
 * Rewrites Java enhanced for loops into appropriate C constructs.
 *
 * @author Keith Stanger
 */
public class EnhancedForRewriter extends TreeVisitor {

  @Override
  public void endVisit(EnhancedForStatement node) {
    Expression expression = node.getExpression();
    ITypeBinding expressionType = expression.getTypeBinding();
    IVariableBinding loopVariable = node.getParameter().getVariableBinding();

    if (BindingUtil.hasAnnotation(loopVariable, AutoreleasePool.class)) {
      makeBlock(node.getBody()).setHasAutoreleasePool(true);
    }

    if (expressionType.isArray()) {
      handleArrayIteration(node);
    } else if (emitJavaIteratorLoop(loopVariable)) {
      convertToJavaIteratorLoop(node);
    } else if (loopVariable.getType().isPrimitive()) {
      boxLoopVariable(node, expressionType, loopVariable);
    } else {
      GeneratedVariableBinding newLoopVariable = new GeneratedVariableBinding(loopVariable);
      newLoopVariable.setTypeQualifiers("__strong");
      node.getParameter().setVariableBinding(newLoopVariable);
    }
  }

  private void handleArrayIteration(EnhancedForStatement node) {
    Expression expression = node.getExpression();
    ITypeBinding expressionType = expression.getTypeBinding();
    IVariableBinding loopVariable = node.getParameter().getVariableBinding();
    ITypeBinding componentType = expressionType.getComponentType();
    ITypeBinding iosArrayType = typeEnv.resolveArrayType(componentType);
    PointerTypeBinding bufferType = typeEnv.getPointerType(componentType);
    IVariableBinding arrayVariable = new GeneratedVariableBinding(
        "a__", 0, expressionType, false, false, null, null);
    GeneratedVariableBinding bufferVariable = new GeneratedVariableBinding(
        "b__", 0, bufferType, false, false, null, null);
    bufferVariable.setTypeQualifiers("const*");
    GeneratedVariableBinding endVariable = new GeneratedVariableBinding(
        "e__", 0, bufferType, false, false, null, null);
    endVariable.setTypeQualifiers("const*");
    VariableElement bufferField = BindingConverter.getVariableElement(new GeneratedVariableBinding(
        "buffer", Modifier.PUBLIC, bufferType, true, false, iosArrayType, null));
    VariableElement sizeField = BindingConverter.getVariableElement(new GeneratedVariableBinding(
        "size", Modifier.PUBLIC, typeEnv.resolveJavaType("int"), true, false, iosArrayType, null));

    VariableDeclarationStatement arrayDecl =
        new VariableDeclarationStatement(arrayVariable, TreeUtil.remove(expression));
    FieldAccess bufferAccess = new FieldAccess(bufferField, new SimpleName(arrayVariable));
    VariableDeclarationStatement bufferDecl =
        new VariableDeclarationStatement(bufferVariable, bufferAccess);
    InfixExpression endInit = new InfixExpression(
        bufferType, InfixExpression.Operator.PLUS, new SimpleName(bufferVariable),
        new FieldAccess(sizeField, new SimpleName(arrayVariable)));
    VariableDeclarationStatement endDecl = new VariableDeclarationStatement(endVariable, endInit);

    WhileStatement loop = new WhileStatement();
    loop.setExpression(new InfixExpression(
        typeEnv.resolveJavaType("boolean"), InfixExpression.Operator.LESS,
        new SimpleName(bufferVariable), new SimpleName(endVariable)));
    Block newLoopBody = makeBlock(TreeUtil.remove(node.getBody()));
    loop.setBody(newLoopBody);
    newLoopBody.addStatement(0, new VariableDeclarationStatement(
        loopVariable, new PrefixExpression(
            BindingConverter.getType(componentType),
            PrefixExpression.Operator.DEREFERENCE,
            new PostfixExpression(
                BindingConverter.getVariableElement(bufferVariable),
                PostfixExpression.Operator.INCREMENT))));

    Block block = new Block();
    List<Statement> stmts = block.getStatements();
    stmts.add(arrayDecl);
    stmts.add(bufferDecl);
    stmts.add(endDecl);
    stmts.add(loop);
    replaceLoop(node, block, loop);
  }

  private boolean emitJavaIteratorLoop(IVariableBinding loopVariable) {
    IAnnotationBinding loopTranslation =
        BindingUtil.getAnnotation(loopVariable, LoopTranslation.class);
    if (loopTranslation == null) {
      return false;
    }
    Object style = BindingUtil.getAnnotationValue(loopTranslation, "value");
    if (style instanceof IVariableBinding
        && ((IVariableBinding) style).getName().equals(LoopStyle.JAVA_ITERATOR.name())) {
      return true;
    }
    return false;
  }

  private void convertToJavaIteratorLoop(EnhancedForStatement node) {
    Expression expression = node.getExpression();
    ITypeBinding expressionType = expression.getTypeBinding();
    IVariableBinding loopVariable = node.getParameter().getVariableBinding();
    ITypeBinding iterableType = BindingUtil.findInterface(expressionType, "java.lang.Iterable");
    IMethodBinding iteratorMethod = BindingUtil.findDeclaredMethod(iterableType, "iterator");
    ITypeBinding iteratorType = iteratorMethod.getReturnType();
    IMethodBinding hasNextMethod = BindingUtil.findDeclaredMethod(iteratorType, "hasNext");
    IMethodBinding nextMethod = BindingUtil.findDeclaredMethod(iteratorType, "next");
    assert hasNextMethod != null && nextMethod != null;

    IVariableBinding iteratorVariable = new GeneratedVariableBinding(
        "iter__", 0, iteratorType, false, false, null, null);

    MethodInvocation iteratorInvocation =
        new MethodInvocation(iteratorMethod, TreeUtil.remove(expression));
    VariableDeclarationStatement iteratorDecl =
        new VariableDeclarationStatement(iteratorVariable, iteratorInvocation);
    MethodInvocation hasNextInvocation =
        new MethodInvocation(hasNextMethod, new SimpleName(iteratorVariable));
    MethodInvocation nextInvocation =
        new MethodInvocation(nextMethod, new SimpleName(iteratorVariable));

    Block newLoopBody = makeBlock(TreeUtil.remove(node.getBody()));
    newLoopBody.addStatement(
        0, new VariableDeclarationStatement(loopVariable, nextInvocation));

    WhileStatement whileLoop = new WhileStatement();
    whileLoop.setExpression(hasNextInvocation);
    whileLoop.setBody(newLoopBody);

    Block block = new Block();
    List<Statement> stmts = block.getStatements();
    stmts.add(iteratorDecl);
    stmts.add(whileLoop);
    replaceLoop(node, block, whileLoop);
  }

  private void replaceLoop(EnhancedForStatement oldLoop, Statement replacement, Statement newLoop) {
    if (oldLoop.getParent() instanceof LabeledStatement) {
      LabeledStatement labeledStmt = (LabeledStatement) oldLoop.getParent();
      labeledStmt.replaceWith(replacement);
      newLoop.replaceWith(labeledStmt);
      labeledStmt.setBody(newLoop);
    } else {
      oldLoop.replaceWith(replacement);
    }
  }

  private void boxLoopVariable(
      EnhancedForStatement node, ITypeBinding expressionType, IVariableBinding loopVariable) {
    ITypeBinding[] typeArgs = expressionType.getTypeArguments();
    if (typeArgs.length == 0) {
      ITypeBinding iterableType = BindingUtil.findInterface(expressionType, "java.lang.Iterable");
      typeArgs = iterableType != null ? iterableType.getTypeArguments() : new ITypeBinding[0];
    }
    assert typeArgs.length == 1 && typeEnv.isBoxedPrimitive(typeArgs[0]);
    IVariableBinding boxVariable = new GeneratedVariableBinding(
        "boxed__", 0, typeArgs[0], false, false, null, null);
    node.setParameter(new SingleVariableDeclaration(boxVariable));
    makeBlock(node.getBody()).addStatement(
        0, new VariableDeclarationStatement(loopVariable, new SimpleName(boxVariable)));
  }

  private Block makeBlock(Statement stmt) {
    if (stmt instanceof Block) {
      return (Block) stmt;
    }
    Block block = new Block();
    if (stmt.getParent() != null) {
      stmt.replaceWith(block);
    }
    block.addStatement(stmt);
    return block;
  }
}
