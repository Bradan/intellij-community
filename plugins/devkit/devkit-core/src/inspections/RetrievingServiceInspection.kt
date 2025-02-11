// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.idea.devkit.inspections

import com.intellij.codeInspection.*
import com.intellij.openapi.application.Application
import com.intellij.openapi.components.ComponentManager
import com.intellij.openapi.components.Service.Level
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.CommonClassNames
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.util.InheritanceUtil
import com.intellij.uast.UastHintedVisitorAdapter
import com.siyeh.ig.callMatcher.CallMatcher
import org.jetbrains.idea.devkit.DevKitBundle
import org.jetbrains.uast.*
import org.jetbrains.uast.generate.UastCodeGenerationPlugin
import org.jetbrains.uast.generate.replace
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor

private val SERVICE_KT_METHODS =
  CallMatcher.staticCall("com.intellij.openapi.components.ServiceKt", "service", "serviceOrNull", "serviceIfCreated")
             .parameterCount(0)
private val SERVICES_KT_METHODS =
  CallMatcher.staticCall("com.intellij.openapi.components.ServicesKt", "service", "serviceOrNull", "serviceIfCreated")
             .parameterTypes(ComponentManager::class.java.canonicalName)

private val COMPONENT_MANAGER_GET_SERVICE = CallMatcher.anyOf(
  CallMatcher.instanceCall(ComponentManager::class.java.canonicalName, "getService").parameterTypes(CommonClassNames.JAVA_LANG_CLASS),
  CallMatcher.instanceCall(ComponentManager::class.java.canonicalName, "getService").parameterTypes(CommonClassNames.JAVA_LANG_CLASS,
                                                                                                    "boolean"),
  SERVICE_KT_METHODS,
  SERVICES_KT_METHODS,
)

internal class RetrievingServiceInspection : DevKitUastInspectionBase() {

  override fun buildInternalVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {

      override fun visitCallExpression(node: UCallExpression): Boolean {
        if (!COMPONENT_MANAGER_GET_SERVICE.uCallMatches(node)) return true
        val howServiceRetrieved = howServiceRetrieved(node) ?: return true
        val serviceType = node.returnType as? PsiClassType ?: return true
        val serviceClass = serviceType.resolve()?.toUElement(UClass::class.java) ?: return true
        val serviceLevel = getLevelType(holder.project, serviceClass)
        if (serviceLevel == LevelType.MODULE) return true
        if (serviceLevel == null) {
          val className = serviceClass.qualifiedName
          if (className != null) {
            val message = DevKitBundle.message("inspection.retrieving.service.not.registered", className)
            holder.registerUProblem(node, message)
          }
        }
        else if (!isServiceRetrievedCorrectly(serviceLevel, howServiceRetrieved)) {
          registerProblemMismatchedRetrieving(howServiceRetrieved, holder, node)
        }
        else {
          val retrievingExpression = node.uastParent as? UQualifiedReferenceExpression ?: return true
          val getInstanceMethod = findGetInstanceMethod(retrievingExpression, howServiceRetrieved, serviceClass)
          if (getInstanceMethod != null) {
            registerProblem(getInstanceMethod, howServiceRetrieved, holder, retrievingExpression)
          }
        }
        return true
      }
    }, arrayOf(UCallExpression::class.java))
  }

  private fun registerProblemMismatchedRetrieving(howServiceRetrieved: Level,
                                                  holder: ProblemsHolder,
                                                  node: UCallExpression) {
    val message = when (howServiceRetrieved) {
      Level.APP -> DevKitBundle.message("inspection.retrieving.service.mismatch.for.project.level")
      Level.PROJECT -> DevKitBundle.message("inspection.retrieving.service.mismatch.for.app.level")
    }
    holder.registerUProblem(node, message)
  }

  private fun isServiceRetrievedCorrectly(serviceLevel: LevelType, howServiceRetrieved: Level): Boolean {
    return serviceLevel == LevelType.NOT_SPECIFIED ||
           when (howServiceRetrieved) {
             Level.APP -> serviceLevel.isApp()
             Level.PROJECT -> serviceLevel.isProject()
           }
  }

  private fun howServiceRetrieved(getServiceCandidate: UCallExpression): Level? {
    if (SERVICE_KT_METHODS.uCallMatches(getServiceCandidate)) return Level.APP
    val receiverType = getServiceCandidate.receiver?.getExpressionType() ?: return null
    val aClass = (receiverType as? PsiClassType)?.resolve() ?: return null
    return when {
      InheritanceUtil.isInheritor(aClass, Application::class.java.canonicalName) -> Level.APP
      InheritanceUtil.isInheritor(aClass, Project::class.java.canonicalName) -> Level.PROJECT
      else -> null
    }
  }

  private fun findGetInstanceMethod(retrievingExpression: UQualifiedReferenceExpression,
                                    howServiceRetrieved: Level,
                                    serviceClass: UClass): UMethod? {
    val returnExpr = retrievingExpression.uastParent as? UReturnExpression
    if (returnExpr != null) {
      val containingMethod = returnExpr.jumpTarget as? UMethod
      if (containingMethod != null) {
        if (howServiceRetrieved == Level.APP && isGetInstanceApplicationLevel(containingMethod)) return null
        if (howServiceRetrieved == Level.PROJECT && isGetInstanceProjectLevel(containingMethod)) return null
      }
    }
    return when (howServiceRetrieved) {
      Level.APP -> findGetInstanceApplicationLevel(serviceClass)
      Level.PROJECT -> findGetInstanceProjectLevel(serviceClass)
    }
  }

  private fun registerProblem(replacementMethod: UMethod,
                              howServiceRetrieved: Level,
                              holder: ProblemsHolder,
                              retrievingExpression: UQualifiedReferenceExpression) {
    val qualifiedName = replacementMethod.getContainingUClass()?.qualifiedName ?: return
    val serviceName = StringUtil.getShortName(qualifiedName)
    val message = DevKitBundle.message("inspection.retrieving.service.can.be.replaced.with", serviceName, replacementMethod.name)
    val fix = ReplaceWithGetInstanceCallFix(serviceName, replacementMethod.name, howServiceRetrieved)
    holder.registerUProblem(retrievingExpression, message, fixes = arrayOf(fix), ProblemHighlightType.WEAK_WARNING)
  }

  private fun findGetInstanceProjectLevel(uClass: UClass): UMethod? {
    return uClass.methods.find { isGetInstanceProjectLevel(it) }
  }

  private fun isGetInstanceProjectLevel(method: UMethod): Boolean {
    if (!(method.isStaticOrJvmStatic &&
          method.visibility == UastVisibility.PUBLIC &&
          method.uastParameters.size == 1)) {
      return false
    }
    val param = method.uastParameters[0]
    if (param.type.canonicalText != Project::class.java.canonicalName) return false
    val qualifiedRef = getReturnExpression(method)?.returnExpression as? UQualifiedReferenceExpression ?: return false
    return COMPONENT_MANAGER_GET_SERVICE.uCallMatches(qualifiedRef.selector as? UCallExpression) &&
           (qualifiedRef.receiver as? USimpleNameReferenceExpression)?.resolveToUElement() == param
  }

  private fun findGetInstanceApplicationLevel(uClass: UClass): UMethod? {
    return uClass.methods.find { isGetInstanceApplicationLevel(it) }
  }

  private fun isGetInstanceApplicationLevel(method: UMethod): Boolean {
    if (!(method.isStaticOrJvmStatic &&
          method.visibility == UastVisibility.PUBLIC &&
          method.uastParameters.isEmpty())) {
      return false
    }
    val qualifiedRef = getReturnExpression(method)?.returnExpression as? UQualifiedReferenceExpression ?: return false
    return COMPONENT_MANAGER_GET_SERVICE.uCallMatches(qualifiedRef.selector as? UCallExpression) &&
           qualifiedRef.receiver.getExpressionType()?.isInheritorOf(Application::class.java.canonicalName) == true
  }

  private val UMethod.isStaticOrJvmStatic: Boolean
    get() = this.isStatic || this.findAnnotation(JvmStatic::class.java.canonicalName) != null

  private fun getReturnExpression(method: UMethod): UReturnExpression? {
    return (method.uastBody as? UBlockExpression)?.expressions?.singleOrNull() as? UReturnExpression
  }

  private class ReplaceWithGetInstanceCallFix(private val serviceName: String,
                                              private val methodName: String,
                                              private val howServiceRetrieved: Level) : LocalQuickFix {

    override fun getFamilyName(): String = DevKitBundle.message("inspection.retrieving.service.replace.with", serviceName, methodName)

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
      val oldCall = descriptor.psiElement.toUElement()?.getParentOfType<UQualifiedReferenceExpression>() ?: return
      val generationPlugin = UastCodeGenerationPlugin.byLanguage(descriptor.psiElement.language) ?: return
      val factory = generationPlugin.getElementFactory(project)
      val serviceName = oldCall.getExpressionType()?.canonicalText ?: return
      val parameters = when (howServiceRetrieved) {
        Level.APP -> emptyList()
        Level.PROJECT -> listOf(oldCall.receiver)
      }
      val context = oldCall.sourcePsi
      val receiver = factory.createQualifiedReference(serviceName, context) ?: factory.createSimpleReference(serviceName, context)
      val newCall = factory.createCallExpression(receiver = receiver,
                                                 methodName = methodName, parameters = parameters,
                                                 expectedReturnType = oldCall.getExpressionType(), kind = UastCallKind.METHOD_CALL,
                                                 context = null) ?: return
      oldCall.replace(newCall)
    }
  }
}
