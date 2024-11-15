/*
* Copyright (C) 2015 Alexander Verbruggen
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Lesser General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Lesser General Public License for more details.
*
* You should have received a copy of the GNU Lesser General Public License
* along with this program. If not, see <https://www.gnu.org/licenses/>.
*/

package be.nabu.glue.soapui;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.apache.xmlbeans.XmlException;

import be.nabu.glue.api.ExecutionContext;
import be.nabu.glue.api.MethodDescription;
import be.nabu.glue.api.ParameterDescription;
import be.nabu.glue.core.api.MethodProvider;
import be.nabu.glue.core.impl.methods.ScriptMethods;
import be.nabu.glue.core.impl.methods.StringMethods;
import be.nabu.glue.core.impl.methods.TestMethods;
import be.nabu.glue.impl.SimpleMethodDescription;
import be.nabu.glue.impl.SimpleParameterDescription;
import be.nabu.glue.utils.ScriptRuntime;
import be.nabu.libs.evaluator.EvaluationException;
import be.nabu.libs.evaluator.api.Operation;
import be.nabu.libs.evaluator.base.BaseMethodOperation;

import com.eviware.soapui.impl.WorkspaceImpl;
import com.eviware.soapui.impl.wsdl.WsdlProject;
import com.eviware.soapui.impl.wsdl.testcase.WsdlTestCaseRunner;
import com.eviware.soapui.model.support.PropertiesMap;
import com.eviware.soapui.model.testsuite.TestCase;
import com.eviware.soapui.model.testsuite.TestRunner;
import com.eviware.soapui.model.testsuite.TestRunner.Status;
import com.eviware.soapui.model.testsuite.TestStepResult;
import com.eviware.soapui.model.testsuite.TestStepResult.TestStepStatus;
import com.eviware.soapui.model.testsuite.TestSuite;
import com.eviware.soapui.support.types.StringToStringMap;

public class SoapUIMethodProvider implements MethodProvider {

	@Override
	public Operation<ExecutionContext> resolve(String name) {
		if (name.equalsIgnoreCase("soapui") || name.equals("soapui.soapui")) {
			return new SoapUIOperation();
		}
		return null;
	}

	@Override
	public List<MethodDescription> getAvailableMethods() {
		List<MethodDescription> descriptions = new ArrayList<MethodDescription>();
		descriptions.add(new SimpleMethodDescription("soapui", "soapui", "This will run a soapUI project file",
			Arrays.asList(new ParameterDescription [] { new SimpleParameterDescription("script", "You can pass in the name of the project file that holds the soapui tests or alternatively you can pass in byte[] or InputStream", "String, byte[], InputStream") } ),
			new ArrayList<ParameterDescription>()));
		return descriptions;
	}

	private static class SoapUIOperation extends BaseMethodOperation<ExecutionContext> {

		@SuppressWarnings({ "rawtypes", "unchecked" })
		@Override
		public Object evaluate(ExecutionContext context) throws EvaluationException {
			List arguments = new ArrayList();
			for (int i = 1; i < getParts().size(); i++) {
				Operation<ExecutionContext> argumentOperation = (Operation<ExecutionContext>) getParts().get(i).getContent();
				arguments.add(argumentOperation.evaluate(context));
			}
			if (arguments.size() == 0) {
				arguments.add("testcase.xml");
			}
			
			// currently workspace files are ignored! 
			// not sure if they hold any important information but it would be nice to be able to work with only the project file
			boolean detailed = Boolean.parseBoolean(System.getProperty("soapui.detailed", "false"));
			try {
				String string = new String(ScriptMethods.bytes(arguments.get(0)), ScriptRuntime.getRuntime().getScript().getCharset());
				ScriptRuntime.getRuntime().getScript().getParser().substitute(string, context, true);
				InputStream input = new ByteArrayInputStream(string.getBytes(ScriptRuntime.getRuntime().getScript().getCharset()));
				
				WsdlProject project = new WsdlProject(input, new WorkspaceImpl(System.getProperty("java.io.tmpdir", ".") + "/" + UUID.randomUUID().toString() + ".xml", new StringToStringMap()));
				ScriptMethods.debug("Project: " + project.getName());
				String originalGroup = null;
				if (context.getCurrent() != null && context.getCurrent().getContext() != null) {
					originalGroup = context.getCurrent().getContext().getAnnotations().get("group");
				}
				for (TestSuite suite : project.getTestSuiteList()) {
					if (ScriptRuntime.getRuntime().isAborted()) {
						break;
					}
					ScriptMethods.debug("Testsuite: " + suite.getName());
					if (context.getCurrent() != null && context.getCurrent().getContext() != null) {
						context.getCurrent().getContext().getAnnotations().put("group", originalGroup == null ? suite.getName() : originalGroup + ":" + suite.getName());
					}
					for (TestCase testCase : suite.getTestCaseList()) {
						if (ScriptRuntime.getRuntime().isAborted()) {
							break;
						}
						ScriptMethods.debug("Testcase: " + testCase.getName());
						TestRunner runner = testCase.run(new PropertiesMap(), false);
						String lastErrorMessage = null;
						if (runner instanceof WsdlTestCaseRunner) {
							WsdlTestCaseRunner wsdlRunner = (WsdlTestCaseRunner) runner;
							for (TestStepResult result : wsdlRunner.getResults()) {
								boolean success = result.getStatus() == TestStepStatus.OK;
								String errorMessage = "unknown";
								if (result.getError() != null) {
									errorMessage = result.getError().getMessage();
								}
								else if (result.getMessages() != null && result.getMessages().length > 0) {
									errorMessage = StringMethods.join(",", result.getMessages()[0]).replaceAll("[\r\n]+", "").replaceAll(">[\\s]+<", "><");
								}
								if (detailed) {
									TestMethods.check(testCase.getName() + " - " + result.getTestStep().getName() + " (" + result.getTimeTaken() + "ms)", success, success ? "true" : errorMessage, false);
								}
								if (!success) {
									lastErrorMessage = errorMessage;
								}
							}
						}
						if (!detailed) {
							boolean success = runner.getStatus() == Status.FINISHED;
							if (!success && lastErrorMessage == null) {
								lastErrorMessage = runner.getReason();
							}
							TestMethods.check(testCase.getName() + " (" + runner.getTimeTaken() + "ms)", success, success ? "true" : lastErrorMessage, false);
						}
					}
				}
				// reset group to original
				if (context.getCurrent() != null && context.getCurrent().getContext() != null) {
					// only reset the group if there was one to start with
					if (originalGroup != null) {
						context.getCurrent().getContext().getAnnotations().put("group", originalGroup);
					}
					// otherwise, simply remove it
					else {
						context.getCurrent().getContext().getAnnotations().remove("group");
					}
				}
			}
			catch (XmlException e) {
				throw new EvaluationException(e);
			}
			catch (IOException e) {
				throw new EvaluationException(e);
			}
			return true;
		}

		@Override
		public void finish() throws ParseException {
			// do nothing
		}
	}
}
