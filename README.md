# SoapUI

The soapui plugin provides a single method: ``soap.soapui()``

If given no parameters, the method will pick up a resource by the name ``testcase.xml``. You can also give a custom resource name or pass in string, for example:

```python
soapui("mytestcase.xml")
```

The file you pass in should be the project file created by soapui.

The method will run all the testsuites with all their testcases and map the results back to the glue validation system.

In case of failure it will show the last failed step. More detail can be gotten by setting ``soapui.detailed = true``
