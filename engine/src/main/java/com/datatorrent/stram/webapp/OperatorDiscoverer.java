/**
 * Copyright (C) 2015 DataTorrent, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datatorrent.stram.webapp;

import com.datatorrent.api.InputOperator;
import com.datatorrent.api.Operator;
import com.datatorrent.netlet.util.DTThrowable;
import com.datatorrent.stram.webapp.TypeDiscoverer.UI_TYPE;
import com.datatorrent.stram.webapp.asm.CompactAnnotationNode;
import com.datatorrent.stram.webapp.asm.CompactFieldNode;
import com.google.common.base.Predicate;
import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import java.beans.*;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.*;
import java.net.*;
import java.util.*;
import java.util.Map.Entry;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.*;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.text.WordUtils;
import org.codehaus.jettison.json.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * <p>OperatorDiscoverer class.</p>
 * Discover Operators.
 * Warning: Using this class may cause classloader leakage, depending on the classes it loads.
 *
 * @since 0.3.2
 */
public class OperatorDiscoverer
{
  public static final String GENERATED_CLASSES_JAR = "dt-generated-classes.jar";

  private static class ClassComparator implements Comparator<Class<?>> {

    @Override
    public int compare(Class<?> a, Class<?> b)
    {
      return a.getName().compareTo(b.getName());
    }

  }
  private final Set<Class<? extends Operator>> operatorClasses = new TreeSet<Class<? extends Operator>>(new ClassComparator());
  private static final Logger LOG = LoggerFactory.getLogger(OperatorDiscoverer.class);
  private final List<String> pathsToScan = new ArrayList<String>();
  private final ClassLoader classLoader;
  private static final String DT_OPERATOR_DOCLINK_PREFIX = "https://www.datatorrent.com/docs/apidocs/index.html";
  public static final String PORT_TYPE_INFO_KEY = "portTypeInfo";
  private final TypeGraph typeGraph = TypeGraphFactory.createTypeGraphProtoType();

  private static final String USE_SCHEMA_TAG = "@useSchema";
  private static final String DESCRIPTION_TAG = "@description";
  private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+?");

  private static final String SCHEMA_REQUIRED_KEY = "schemaRequired";

  private final Map<String, OperatorClassInfo> classInfo = new HashMap<String, OperatorClassInfo>();

  private static class OperatorClassInfo {
    String comment;
    final Map<String, String> tags = new HashMap<String, String>();
    final Map<String, MethodInfo> getMethods = Maps.newHashMap();
    final Map<String, MethodInfo> setMethods = Maps.newHashMap();
    final Set<String> invisibleGetSetMethods = new HashSet<String>();
    final Map<String, String> fields = new HashMap<String, String>();
  }

  private static class MethodInfo
  {
    Map<String, String> descriptions = Maps.newHashMap();
    Map<String, String> useSchemas = Maps.newHashMap();
    String comment;
  }

  private class JavadocSAXHandler extends DefaultHandler {

    private String className = null;
    private OperatorClassInfo oci = null;
    private StringBuilder comment;
    private String fieldName = null;
    private String methodName = null;
    private final Pattern getterPattern = Pattern.compile("(?:is|get)[A-Z].*");
    private final Pattern setterPattern = Pattern.compile("(?:set)[A-Z].*");

    private boolean isGetter(String methodName)
    {
      return getterPattern.matcher(methodName).matches();
    }

    private boolean isSetter(String methodName)
    {
      return setterPattern.matcher(methodName).matches();
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes)
            throws SAXException
    {
      if (qName.equalsIgnoreCase("class")) {
        className = attributes.getValue("qualified");
        oci = new OperatorClassInfo();
      }
      else if (qName.equalsIgnoreCase("comment")) {
        comment = new StringBuilder();
      }
      else if (qName.equalsIgnoreCase("tag")) {
        if (oci != null) {
          String tagName = attributes.getValue("name");
          String tagText = attributes.getValue("text").trim();
          if (methodName != null) {
            boolean lGetterCheck = isGetter(methodName);
            boolean lSetterCheck = !lGetterCheck && isSetter(methodName);

            if (lGetterCheck || lSetterCheck) {
              if ("@omitFromUI".equals(tagName)) {
                oci.invisibleGetSetMethods.add(methodName);
              }
              else if (DESCRIPTION_TAG.equals(tagName)) {
                addTagToMethod(lGetterCheck ? oci.getMethods : oci.setMethods, tagText, true);
              }
              else if (USE_SCHEMA_TAG.equals(tagName)) {
                addTagToMethod(lGetterCheck ? oci.getMethods : oci.setMethods, tagText, false);
              }
            }
//            if ("@return".equals(tagName) && isGetter(methodName)) {
//              oci.getMethods.put(methodName, tagText);
//            }
            //do nothing
          }
          else if (fieldName != null) {
            // do nothing
          }
          else {
            oci.tags.put(tagName, tagText);
          }
        }
      }
      else if (qName.equalsIgnoreCase("field")) {
        fieldName = attributes.getValue("name");
      }
      else if (qName.equalsIgnoreCase("method")) {
        methodName = attributes.getValue("name");
      }
    }

    private void addTagToMethod(Map<String, MethodInfo> methods, String tagText, boolean isDescription)
    {
      MethodInfo mi = methods.get(methodName);
      if (mi == null) {
        mi = new MethodInfo();
        methods.put(methodName, mi);
      }
      String[] tagParts = Iterables.toArray(Splitter.on(WHITESPACE_PATTERN).trimResults().omitEmptyStrings().
        limit(2).split(tagText), String.class);
      if (tagParts.length == 2) {
        if (isDescription) {
          mi.descriptions.put(tagParts[0], tagParts[1]);
        }
        else {
          mi.useSchemas.put(tagParts[0], tagParts[1]);
        }
      }
    }

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
      if (qName.equalsIgnoreCase("class")) {
        classInfo.put(className, oci);
        className = null;
        oci = null;
      }
      else if (qName.equalsIgnoreCase("comment") && oci != null) {
        if (methodName != null) {
          // do nothing
          if (isGetter(methodName)) {
            MethodInfo mi = oci.getMethods.get(methodName);
            if (mi == null) {
              mi = new MethodInfo();
              oci.getMethods.put(methodName, mi);
            }
            mi.comment = comment.toString();
          } else if (isSetter(methodName)) {
            MethodInfo mi = oci.setMethods.get(methodName);
            if (mi == null) {
              mi = new MethodInfo();
              oci.setMethods.put(methodName, mi);
            }
            mi.comment = comment.toString();
          }
        }
        else if (fieldName != null) {
          oci.fields.put(fieldName, comment.toString());
        }
        else {
          oci.comment = comment.toString();
        }
        comment = null;
      }
      else if (qName.equalsIgnoreCase("field")) {
        fieldName = null;
      }
      else if (qName.equalsIgnoreCase("method")) {
        methodName = null;
      }
    }

    @Override
    public void characters(char ch[], int start, int length) throws SAXException {
      if (comment != null) {
        comment.append(ch, start, length);
      }
    }
  }

  public OperatorDiscoverer()
  {
    classLoader = ClassLoader.getSystemClassLoader();
  }

  public OperatorDiscoverer(String[] jars)
  {
    URL[] urls = new URL[jars.length];
    for (int i = 0; i < jars.length; i++) {
      pathsToScan.add(jars[i]);
      try {
        urls[i] = new URL("file://" + jars[i]);
      }
      catch (MalformedURLException ex) {
        throw new RuntimeException(ex);
      }
    }
    classLoader = new URLClassLoader(urls, ClassLoader.getSystemClassLoader());
  }


  @SuppressWarnings({ "unchecked" })
  private void loadOperatorClass()
  {
    buildTypeGraph();
    String operatorRoot = Operator.class.getName();
    Set<String> allOperatorClasses = typeGraph.getDescendants(operatorRoot);
//    ClassLoader cLoader = new URLClassLoader();
    for (String opClassName : allOperatorClasses) {
      try {
        Class<?> clazz = classLoader.loadClass(opClassName);
//        typeGraph.get(opClassName).loadedClass = clazz;
        if (isInstantiableOperatorClass(clazz)) {
          LOG.debug("Adding class {} as an operator", clazz.getName());
          operatorClasses.add((Class<? extends Operator>)clazz);
        }
      }
      catch (Throwable ex) {
        LOG.warn("Class cannot be loaded: {} (error was {})", opClassName, ex.getMessage());
      }
    }
  }

  public void buildTypeGraph()
  {
    Map<String, JarFile> openJarFiles = new HashMap<String, JarFile>();
    Map<String, File> openClassFiles = new HashMap<String, File>();
    try {
      for (String path : pathsToScan) {
        File f = null;
        try {
          f = new File(path);
          if (!f.exists() || f.isDirectory() || (!f.getName().endsWith("jar") && !f.getName().endsWith("class"))) {
            continue;
          }
          if(GENERATED_CLASSES_JAR.equals(f.getName())) {
            continue;
          }
          if (f.getName().endsWith("class")) {
            typeGraph.addNode(f);
            openClassFiles.put(path, f);
          } else {
            JarFile jar = new JarFile(path);
            openJarFiles.put(path, jar);
            java.util.Enumeration<JarEntry> entriesEnum = jar.entries();
            while (entriesEnum.hasMoreElements()) {
              java.util.jar.JarEntry jarEntry = entriesEnum.nextElement();
              if (!jarEntry.isDirectory() && jarEntry.getName().endsWith("-javadoc.xml")) {
                try {
                  processJavadocXml(jar.getInputStream(jarEntry));
                  // break;
                } catch (Exception ex) {
                  LOG.warn("Cannot process javadoc {} : ", jarEntry.getName(), ex);
                }
              } else if (!jarEntry.isDirectory() && jarEntry.getName().endsWith(".class")) {
                typeGraph.addNode(jarEntry, jar);
              }
            }
          }
        } catch (IOException ex) {
          LOG.warn("Cannot process file {}", f, ex);
        }
      }

      typeGraph.updatePortTypeInfoInTypeGraph(openJarFiles, openClassFiles);
    }
   finally {
      for (Entry<String, JarFile> entry : openJarFiles.entrySet()) {
        try {
          entry.getValue().close();
        } catch (IOException e) {
          DTThrowable.wrapIfChecked(e);
        }
      }
    }
  }

  private void processJavadocXml(InputStream is) throws ParserConfigurationException, SAXException, IOException
  {
    SAXParserFactory saxParserFactory = SAXParserFactory.newInstance();
    saxParserFactory.newSAXParser().parse(is, new JavadocSAXHandler());
  }

  public static boolean isInstantiableOperatorClass(Class<?> clazz)
  {
    int modifiers = clazz.getModifiers();
    if (Modifier.isAbstract(modifiers) || Modifier.isInterface(modifiers) || !Operator.class.isAssignableFrom(clazz)){
      return false;
    }
    // return true if it is an InputOperator or if it is an Operator with more than one InputPort
    //TODO Use ASM later
    return InputOperator.class.isAssignableFrom(clazz) || Iterables.any(Arrays.asList(clazz.getFields()), new Predicate<Field>() {
      @Override
      public boolean apply(Field f)
      {
        return Operator.InputPort.class.isAssignableFrom(f.getType());
      }
    });
  }

  public Set<Class<? extends Operator>> getOperatorClasses(String parent, String searchTerm) throws ClassNotFoundException
  {
    if (operatorClasses.isEmpty()) {
      loadOperatorClass();
    }
    Class<?> parentClass;
    if (parent == null) {
      parentClass = Operator.class;
    }
    else {
      parentClass = classLoader.loadClass(parent);
      if (!Operator.class.isAssignableFrom(parentClass)) {
        throw new IllegalArgumentException("Argument must be a subclass of Operator class");
      }
    }
    Set<Class<? extends Operator>> filteredClass = Sets.filter(operatorClasses, new Predicate<Class<? extends Operator>>() {

      @Override
      public boolean apply(Class<? extends Operator> c)
      {
        OperatorClassInfo oci = classInfo.get(c.getName());
        return oci == null || !oci.tags.containsKey("@omitFromUI");
      }
    });
    if (searchTerm == null && parentClass == Operator.class) {
      return Collections.unmodifiableSet(filteredClass);
    }
    if (searchTerm != null) {
      searchTerm = searchTerm.toLowerCase();
    }
    Set<Class<? extends Operator>> result = new HashSet<Class<? extends Operator>>();
    for (Class<? extends Operator> clazz : filteredClass) {
      if (parentClass.isAssignableFrom(clazz)) {
        if (searchTerm == null) {
          result.add(clazz);
        }
        else {
          if (clazz.getName().toLowerCase().contains(searchTerm)) {
            result.add(clazz);
          }
          else {
            OperatorClassInfo oci = classInfo.get(clazz.getName());
            if (oci != null) {
              if (oci.comment != null && oci.comment.toLowerCase().contains(searchTerm)) {
                result.add(clazz);
              }
              else {
                for (Map.Entry<String, String> entry : oci.tags.entrySet()) {
                  if (entry.getValue().toLowerCase().contains(searchTerm)) {
                    result.add(clazz);
                    break;
                  }
                }
              }
            }
          }
        }
      }
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  public Class<? extends Operator> getOperatorClass(String className) throws ClassNotFoundException
  {
    if (operatorClasses.isEmpty()) {
      loadOperatorClass();
    }

    Class<?> clazz = classLoader.loadClass(className);

    if (!Operator.class.isAssignableFrom(clazz)) {
      throw new IllegalArgumentException("Argument must be a subclass of Operator class");
    }
    return (Class<? extends Operator>)clazz;
  }

  public JSONObject describeOperator(Class<? extends Operator> clazz) throws Exception
  {
    if (OperatorDiscoverer.isInstantiableOperatorClass(clazz)) {
      JSONObject response = new JSONObject();
      JSONArray inputPorts = new JSONArray();
      JSONArray outputPorts = new JSONArray();
      // Get properties from ASM

      JSONObject operatorDescriptor =  describeClassByASM(clazz.getName());
      JSONArray properties = operatorDescriptor.getJSONArray("properties");

      properties = enrichProperties(clazz, properties);

      JSONArray portTypeInfo = operatorDescriptor.getJSONArray("portTypeInfo");

      List<CompactFieldNode> inputPortfields = typeGraph.getAllInputPorts(clazz.getName());
      List<CompactFieldNode> outputPortfields = typeGraph.getAllOutputPorts(clazz.getName());


      try {
        for (CompactFieldNode field : inputPortfields) {
          JSONObject inputPort = setFieldAttributes(clazz, field);
          if (!inputPort.has("optional")) {
            inputPort.put("optional", false); // input port that is not annotated is default to be not optional
          }
          if(!inputPort.has(SCHEMA_REQUIRED_KEY)) {
            inputPort.put(SCHEMA_REQUIRED_KEY, false);
          }
          inputPorts.put(inputPort);
        }

        for (CompactFieldNode field : outputPortfields) {
          JSONObject outputPort = setFieldAttributes(clazz, field);

          if (!outputPort.has("optional")) {
            outputPort.put("optional", true); // output port that is not annotated is default to be optional
          }
          if (!outputPort.has("error")) {
            outputPort.put("error", false);
          }
          if(!outputPort.has(SCHEMA_REQUIRED_KEY)) {
            outputPort.put(SCHEMA_REQUIRED_KEY, false);
          }
          outputPorts.put(outputPort);
        }

        response.put("name", clazz.getName());
        response.put("properties", properties);
        response.put(PORT_TYPE_INFO_KEY, portTypeInfo);
        response.put("inputPorts", inputPorts);
        response.put("outputPorts", outputPorts);

        OperatorClassInfo oci = classInfo.get(clazz.getName());

        if (oci != null) {
          if (oci.comment != null) {
            String[] descriptions;
            // first look for a <p> tag
            String keptPrefix = "<p>";
            descriptions = oci.comment.split("<p>", 2);
            if (descriptions.length == 0) {
              keptPrefix = "";
              // if no <p> tag, then look for a blank line
              descriptions = oci.comment.split("\n\n", 2);
            }
            if (descriptions.length > 0) {
              response.put("shortDesc", descriptions[0]);
            }
            if (descriptions.length > 1) {
              response.put("longDesc", keptPrefix + descriptions[1]);
            }
          }
          response.put("category", oci.tags.get("@category"));
          String displayName = oci.tags.get("@displayName");
          if (displayName == null) {
            displayName = decamelizeClassName(clazz.getSimpleName());
          }
          response.put("displayName", displayName);
          String tags = oci.tags.get("@tags");
          if (tags != null) {
            JSONArray tagArray = new JSONArray();
            for (String tag : StringUtils.split(tags, ',')) {
              tagArray.put(tag.trim().toLowerCase());
            }
            response.put("tags", tagArray);
          }
          String doclink = oci.tags.get("@doclink");
          if (doclink != null) {
            response.put("doclink", doclink + "?" + getDocName(clazz));
          }
          else if (clazz.getName().startsWith("com.datatorrent.lib.") ||
                  clazz.getName().startsWith("com.datatorrent.contrib.")) {
            response.put("doclink", DT_OPERATOR_DOCLINK_PREFIX + "?" + getDocName(clazz));
          }
        }
      }
      catch (JSONException ex) {
        throw new RuntimeException(ex);
      }
      return response;
    }
    else {
      throw new UnsupportedOperationException();
    }
  }

  private JSONObject setFieldAttributes(Class<? extends Operator> clazz,
      CompactFieldNode field) throws JSONException {
    JSONObject port = new JSONObject();
    port.put("name", field.getName());

    for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
      OperatorClassInfo oci = classInfo.get(c.getName());
      if (oci != null) {
        String fieldDesc = oci.fields.get(field.getName());
        if (fieldDesc != null) {
          port.put("description", fieldDesc);
          break;
        }
      }
    }

    List<CompactAnnotationNode> annotations = field.getVisibleAnnotations();
    CompactAnnotationNode firstAnnotation;
    if (annotations != null
        && !annotations.isEmpty()
        && (firstAnnotation = field
        .getVisibleAnnotations().get(0)) != null) {
      for(Map.Entry<String, Object> entry :firstAnnotation.getAnnotations().entrySet() ) {
        port.put(entry.getKey(), entry.getValue());
      }
    }

    return port;
  }

  private JSONArray enrichProperties(Class<?> operatorClass, JSONArray properties) throws JSONException
  {
    JSONArray result = new JSONArray();
    for (int i = 0; i < properties.length(); i++) {
      JSONObject propJ = properties.getJSONObject(i);
      String propName = WordUtils.capitalize(propJ.getString("name"));
      String getPrefix = (propJ.getString("type").equals("boolean") || propJ.getString("type").equals("java.lang.Boolean")) ? "is" : "get";
      String setPrefix = "set";
      OperatorClassInfo oci = getOperatorClassWithGetterSetter(operatorClass, setPrefix + propName, getPrefix + propName);
      if(oci == null) {
        result.put(propJ);
        continue;
      }
      if (oci.invisibleGetSetMethods.contains(getPrefix + propName) || oci.invisibleGetSetMethods.contains(setPrefix + propName)) {
        continue;
      }
      MethodInfo methodInfo = oci.setMethods.get(setPrefix + propName);
      methodInfo = methodInfo == null ? oci.getMethods.get(getPrefix + propName) : methodInfo;
      if (methodInfo != null) {
        addTagsToProperties(methodInfo, propJ);
      }
      result.put(propJ);
    }
    return result;
  }

  private OperatorClassInfo getOperatorClassWithGetterSetter(Class<?> operatorClass, String setterName, String getterName) {
    if(operatorClass != null && !Operator.class.isAssignableFrom(operatorClass)){
      return null;
    }
    OperatorClassInfo oci = classInfo.get(operatorClass.getName());
    if(oci != null && (oci.getMethods.containsKey(getterName) || oci.setMethods.containsKey(setterName))){
      return oci;
    } else {
      return getOperatorClassWithGetterSetter(operatorClass.getSuperclass(), setterName, getterName);
    }
  }

  private void addTagsToProperties(MethodInfo mi, JSONObject propJ) throws JSONException
  {
    //create description object
    JSONObject descriptionObj = new JSONObject();
    if (mi.comment != null) {
      descriptionObj.put("$", mi.comment);
    }
    for (Map.Entry<String, String> descEntry : mi.descriptions.entrySet()) {
      descriptionObj.put(descEntry.getKey(), descEntry.getValue());
    }
    if (descriptionObj.length() > 0) {
      propJ.put("descriptions", descriptionObj);
    }

    //create userSchema object
    JSONObject useSchemaObj = new JSONObject();
    for (Map.Entry<String, String> useSchemaEntry : mi.useSchemas.entrySet()) {
      useSchemaObj.put(useSchemaEntry.getKey(), useSchemaEntry.getValue());
    }
    if (useSchemaObj.length() > 0) {
      propJ.put("useSchema", useSchemaObj);
    }
  }

  public JSONObject describeClass(String clazzName) throws Exception
  {
    return describeClassByASM(clazzName);
  }


  public JSONObject describeClassByASM(String clazzName) throws Exception
  {
    return typeGraph.describeClass(clazzName);
  }



  public JSONObject describeClass(Class<?> clazz) throws Exception
  {
    JSONObject desc = new JSONObject();
    desc.put("name", clazz.getName());
    if (clazz.isEnum()) {
      @SuppressWarnings("unchecked")
      Class<Enum<?>> enumClass = (Class<Enum<?>>)clazz;
      ArrayList<String> enumNames = Lists.newArrayList();
      for (Enum<?> e : enumClass.getEnumConstants()) {
        enumNames.add(e.name());
      }
      desc.put("enum", enumNames);
    }
    UI_TYPE ui_type = UI_TYPE.getEnumFor(clazz);
    if(ui_type!=null){
      desc.put("uiType", ui_type.getName());
    }
    desc.put("properties", getClassProperties(clazz, 0));
    return desc;
  }


  private static String getDocName(Class<?> clazz)
  {
    return clazz.getName().replace('.', '/').replace('$', '.') + ".html";
  }

  private JSONArray getClassProperties(Class<?> clazz, int level) throws IntrospectionException
  {
    JSONArray arr = new JSONArray();
    TypeDiscoverer td = new TypeDiscoverer();
    try {
      for (PropertyDescriptor pd : Introspector.getBeanInfo(clazz).getPropertyDescriptors()) {
        Method readMethod = pd.getReadMethod();
        if (readMethod != null) {
          if (readMethod.getDeclaringClass() == java.lang.Enum.class) {
            // skip getDeclaringClass
            continue;
          } else if ("class".equals(pd.getName())) {
            // skip getClass
            continue;
          }
        } else {
          // yields com.datatorrent.api.Context on JDK6 and com.datatorrent.api.Context.OperatorContext with JDK7
          if ("up".equals(pd.getName()) && com.datatorrent.api.Context.class.isAssignableFrom(pd.getPropertyType())) {
            continue;
          }
        }
        //LOG.info("name: " + pd.getName() + " type: " + pd.getPropertyType());

          Class<?> propertyType = pd.getPropertyType();
          if (propertyType != null) {
            JSONObject propertyObj = new JSONObject();
            propertyObj.put("name", pd.getName());
            propertyObj.put("canGet", readMethod != null);
            propertyObj.put("canSet", pd.getWriteMethod() != null);
            if (readMethod != null) {
              for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
                OperatorClassInfo oci = classInfo.get(c.getName());
                if (oci != null) {
                  MethodInfo getMethodInfo = oci.getMethods.get(readMethod.getName());
                  if (getMethodInfo != null) {
                    addTagsToProperties(getMethodInfo, propertyObj);
                    break;
                  }
                }
              }
              // type can be a type symbol or parameterized type
              td.setTypeArguments(clazz, readMethod.getGenericReturnType(), propertyObj);
            } else {
              if (pd.getWriteMethod() != null) {
                td.setTypeArguments(clazz, pd.getWriteMethod().getGenericParameterTypes()[0], propertyObj);
              }
            }
            //if (!propertyType.isPrimitive() && !propertyType.isEnum() && !propertyType.isArray() && !propertyType.getName().startsWith("java.lang") && level < MAX_PROPERTY_LEVELS) {
            //  propertyObj.put("properties", getClassProperties(propertyType, level + 1));
            //}
            arr.put(propertyObj);
          }
      }
    }
    catch (JSONException ex) {
      throw new RuntimeException(ex);
    }
    return arr;
  }

  private static final Pattern CAPS = Pattern.compile("([A-Z\\d][^A-Z\\d]*)");

  private static String decamelizeClassName(String className)
  {
    Matcher match = CAPS.matcher(className);
    StringBuilder deCameled = new StringBuilder();
    while (match.find()) {
      if (deCameled.length() == 0) {
        deCameled.append(match.group());
      }
      else {
        deCameled.append(" ");
        deCameled.append(match.group().toLowerCase());
      }
    }
    return deCameled.toString();
  }

  /**
   * Enrich portClassHier with class/interface names that map to a list of parent classes/interfaces.
   * For any class encountered, find its parents too.
   *
   * @param oper Operator to work on
   * @param portClassHier In-Out param that contains a mapping of class/interface to its parents
   */
  public void buildPortClassHier(JSONObject oper, JSONObject portClassHier) {
    try {
      JSONArray ports = oper.getJSONArray(OperatorDiscoverer.PORT_TYPE_INFO_KEY);
      int num_ports = ports.length();
      for (int i = 0; i < num_ports; i++) {
        JSONObject port = ports.getJSONObject(i);

        String type;
        try {
          type = port.getString("type");
        } catch (JSONException e) {
          // no type key
          continue;
        }

        try {
          // load the port type class
          Class<?> portClazz = classLoader.loadClass(type.replaceAll("\\bclass ", "").replaceAll("\\binterface ", ""));

          // iterate up the class hierarchy to populate the portClassHier map
          while (portClazz != null) {
            ArrayList<String> parents = new ArrayList<String>();

            String portClazzName = portClazz.toString();
            if (portClassHier.has(portClazzName)) {
              // already present in portClassHier, so we can stop
              break;
            }

            // interfaces and Object are at the top of the tree, so we can just put them
            // in portClassHier with empty parents, then move on.
            if (portClazz.isInterface() || portClazzName.equals("java.lang.Object")) {
              portClassHier.put(portClazzName, parents);
              break;
            }

            // look at superclass first
            Class<?> superClazz = portClazz.getSuperclass();
            try {
              String superClazzName = superClazz.toString();
              parents.add(superClazzName);
            } catch (NullPointerException e) {
              LOG.info("Superclass is null for `{}` ({})", portClazz, superClazz);
            }
            // then look at interfaces implemented in this port
            for (Class<?> intf : portClazz.getInterfaces()) {
              String intfName = intf.toString();
              if (!portClassHier.has(intfName)) {
                // add the interface to portClassHier
                portClassHier.put(intfName, new ArrayList<String>());
              }
              parents.add(intfName);
            }

            // now store class=>parents mapping in portClassHier
            portClassHier.put(portClazzName, parents);

            // walk up the hierarchy for the next iteration
            portClazz = superClazz;
          }
        } catch (ClassNotFoundException e) {
          LOG.info("Could not make class from `{}`", type);
        }
      }
    } catch (JSONException e) {
      // should not reach this
      LOG.error("JSON Exception {}", e);
      throw new RuntimeException(e);
    }
  }

  public JSONArray getDescendants(String fullClassName)
  {
    if(typeGraph.size()==0){
      buildTypeGraph();
    }
    return new JSONArray(typeGraph.getDescendants(fullClassName));
  }


  public TypeGraph getTypeGraph()
  {
    return typeGraph;
  }

  public void findPortTypesWithSchemaClasses(JSONObject operator, JSONObject portTypesWithSchemaClasses) throws JSONException
  {
    if (typeGraph.size() == 0) {
      buildTypeGraph();
    }
    JSONArray ports = operator.getJSONArray(OperatorDiscoverer.PORT_TYPE_INFO_KEY);
    for (int i = 0; i < ports.length(); i++) {
      JSONObject port = ports.getJSONObject(i);
      String portType = port.optString("type");
      if (portType == null) {
        continue;
      }
      if (portType.equals("java.lang.Object")) {
        //skipping as this will be true and UI will handle this in a special way.
        continue;
      }
      if (portTypesWithSchemaClasses.has(portType)) {
        //already present in the json so skipping
        continue;
      }
      try {
        JSONObject portTypeDesc = describeClassByASM(portType);
        if (portTypeDesc.has("typeArgs")) {
          //ignoring any type with generics
          continue;
        }
        boolean hasSchemaClasses = false;
        for (String descendent : typeGraph.getInitializableDescendants(portType)) {
          try {
            JSONObject descendentDesc = describeClassByASM(descendent);
            if (descendentDesc.has("typeArgs")) {
              //ignoring any type with generics since that cannot be a schema class
              continue;
            }
            JSONArray descendentProps = descendentDesc.optJSONArray("properties");
            if (descendentProps == null || descendentProps.length() == 0) {
              //no properties then cannot be a schema class
              continue;
            }
            for (int p = 0; p < descendentProps.length(); p++) {
              JSONObject propDesc = descendentProps.getJSONObject(p);
              if (propDesc.optBoolean("canGet", false)) {
                hasSchemaClasses = true;
                break;
              }
            }
            if (hasSchemaClasses) {
              break;
            }
          }
          catch (Exception ex) {
            LOG.warn("describing {}", descendent);
          }
        }
        portTypesWithSchemaClasses.put(portType, hasSchemaClasses);
      }
      catch (Exception ex) {
        LOG.warn("describing {}", portType);
      }
    }
  }

}
