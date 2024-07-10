/*
 * Copyright 2022-2024 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.fhir.workflow

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.context.FhirVersionEnum
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.FhirEngineProvider
import com.google.android.fhir.knowledge.KnowledgeManager
import com.google.android.fhir.workflow.testing.CqlBuilder
import com.google.android.fhir.workflow.testing.FhirEngineProviderTestRule
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.InputStream
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.DecimalType
import org.hl7.fhir.r4.model.Library
import org.hl7.fhir.r4.model.Parameters
import org.hl7.fhir.r4.model.StringType
import org.intellij.lang.annotations.Language
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FhirOperatorLibraryEvaluateJavaTest {

  @get:Rule val fhirEngineProviderRule = FhirEngineProviderTestRule()

  private lateinit var fhirEngine: FhirEngine
  private lateinit var fhirOperator: FhirOperator

  private val context: Context = ApplicationProvider.getApplicationContext()
  private val knowledgeManager = KnowledgeManager.create(context = context, inMemory = true)
  private val fhirContext = FhirContext.forCached(FhirVersionEnum.R4)
  private val jsonParser = fhirContext.newJsonParser()

  private fun open(asset: String): InputStream? {
    return javaClass.getResourceAsStream(asset)
  }

  private fun load(asset: String) = jsonParser.parseResource(open(asset))

  @Before
  fun setUp() = runBlocking {
    fhirEngine = FhirEngineProvider.getInstance(context)
    fhirOperator = FhirOperator(fhirContext, fhirEngine, knowledgeManager)
  }

  /**
   * Evaluates a compiled CQL that was exported to Json and included inside a FHIRLibrary. The
   * compiled CQL file is encoded in Base64 and placed inside the JSON Library. The expression
   * `CompletedImmunization` simply checks if a vaccination protocol has been finished as below.
   *
   * This test requires the CQLEvaluator to
   * 1. load the patient using a `FhirEngineRetrieveProvider`,
   * 2. load the Immunization records of that patient,
   * 3. load the CQL Library using a `FhirEngineLibraryContentProvider`
   * 4. evaluate if the immunization record presents a Protocol where the number of doses taken
   *    matches the number of required doses or if the number of required doses is null.
   *
   * ```
   * library ImmunityCheck version '1.0.0'
   *
   * using FHIR version '4.0.0'
   * include "FHIRHelpers" version '4.0.0' called FHIRHelpers
   * context Immunization
   *
   * define "CompletedImmunization":
   *   exists(GetFinalDose) or exists(GetSingleDose)
   *
   * define "GetFinalDose":
   *   [Immunization] I
   *     where exists(I.protocolApplied)
   *     and I.protocolApplied.doseNumber.value = I.protocolApplied.seriesDoses.value
   *
   * define "GetSingleDose":
   *   [Immunization] I
   *     where exists(I.protocolApplied)
   *     and exists(I.protocolApplied.doseNumber.value)
   *     and not exists(I.protocolApplied.seriesDoses.value)
   * ```
   */
  @Test
  fun evaluateImmunityCheck() = runBlockingOnWorkerThread {
    // Load patient
    val patientImmunizationHistory = load("/immunity-check/ImmunizationHistory.json") as Bundle
    for (entry in patientImmunizationHistory.entry) {
      fhirEngine.create(entry.resource)
    }

    // Load Library that checks if Patient has taken a vaccine
    knowledgeManager.install(writeToFile(load("/immunity-check/ImmunityCheck.json") as Library))
    knowledgeManager.install(writeToFile(load("/immunity-check/FhirHelpers.json") as Library))

    // Evaluates a specific Patient
    val results =
      fhirOperator.evaluateLibrary(
        libraryUrl = "http://localhost/Library/ImmunityCheck|1.0.0",
        patientId = "d4d35004-24f8-40e4-8084-1ad75924514f",
        expressions = setOf("CompletedImmunization"),
      ) as Parameters

    println(jsonParser.setPrettyPrint(true).encodeResourceToString(results))

    assertThat(results.getParameterBool("CompletedImmunization")).isTrue()
  }

  @Test
  fun evaluateSmartHivIGAdministrativeStratifier() = runBlockingOnWorkerThread {
    // Load patient
    val patients = load("/hiv-cds-test/Patients-bundle.json") as Bundle

    // Load Observations
    val observationsBundle =
      load("/hiv-cds-test/indicator_bundle.json") as Bundle

    for (entry in patients.entry) {
      fhirEngine.create(entry.resource)
    }

    for (entry in observationsBundle.entry) {
      fhirEngine.create(entry.resource)
    }

    knowledgeManager.install(writeToFile(load("/hiv-cds-test/libraries/Library-FHIRHelpersLogic.json") as Library))
    knowledgeManager.install(writeToFile(load("/hiv-cds-test/libraries/Library-FHIRCommonLogic.json") as Library))
    knowledgeManager.install(writeToFile(load("/hiv-cds-test/libraries/Library-HIVConceptsLogic.json") as Library))
    knowledgeManager.install(writeToFile(load("/hiv-cds-test/libraries/Library-HIVConceptsCustomLogic.json") as Library))
    knowledgeManager.install(writeToFile(load("/hiv-cds-test/libraries/Library-WHOCommonLogic.json") as Library))
    knowledgeManager.install(writeToFile(load("/hiv-cds-test/libraries/Library-HIVIndicatorCommonLogic.json") as Library))

    // Load Library that checks if Patient's gender (Stratifier)
    knowledgeManager.install(writeToFile(load("/hiv-cds-test/Library-HIVIND19Logic.json") as Library))

    // Evaluates a specific Patient
    val results =
      fhirOperator.evaluateLibrary(
        libraryUrl = "http://smart.who.int/HIV/Library/HIVIND19Logic",
        patientId = "ab0ccb5a-a20d-4159-a438-4845d22af90c",
        expressions = setOf("Administrative Gender Stratifier"),
      ) as Parameters

    println(jsonParser.setPrettyPrint(true).encodeResourceToString(results))

    assertThat(results.getParameterValue("Administrative Gender Stratifier")
      .getChildByName("display").values[0].toString())
      .isEqualTo("Male")
  }


  @Test
  fun evaluateCQL() = runBlockingOnWorkerThread {
    @Language("CQL")
    val cql =
      """
      library TestGetName version '1.0.0'
      
      define GetName: 'MyName'
            """
        .trimIndent()

    val library = CqlBuilder.assembleFhirLib(cql, null, null, "TestGetName", "1.0.0")

    knowledgeManager.install(writeToFile(library))

    // Evaluates expression without any extra data
    val results =
      fhirOperator.evaluateLibrary(libraryUrl = library.url, expressions = setOf("GetName"))
        as Parameters

    assertThat((results.parameterFirstRep.value as StringType).value).isEqualTo("MyName")
  }

  @Test
  fun evaluateCQLWithParameters() = runBlockingOnWorkerThread {
    @Language("CQL")
    val cql =
      """
      library TestSumWithParams version '1.0.0'
      
      parameter "MyNumber" Decimal
      
      define SumOne: MyNumber + 1
            """
        .trimIndent()

    val library = CqlBuilder.assembleFhirLib(cql, null, null, "TestSumWithParams", "1.0.0")

    knowledgeManager.install(writeToFile(library))

    val params =
      Parameters().apply {
        addParameter().apply {
          name = "MyNumber"
          value = DecimalType(1)
        }
      }

    // Evaluates the library with a parameter
    val results =
      fhirOperator.evaluateLibrary(
        libraryUrl = library.url,
        parameters = params,
        expressions = setOf("SumOne"),
      ) as Parameters

    assertThat((results.parameterFirstRep.value as DecimalType).value).isEqualTo(BigDecimal(2))
  }

  private fun writeToFile(library: Library): File {
    return File(context.filesDir, library.name).apply {
      writeText(jsonParser.encodeResourceToString(library))
    }
  }
}
