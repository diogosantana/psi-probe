/*
 * Licensed under the GPL License. You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 * 
 * THIS PACKAGE IS PROVIDED "AS IS" AND WITHOUT ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING,
 * WITHOUT LIMITATION, THE IMPLIED WARRANTIES OF MERCHANTIBILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE.
 */

package com.googlecode.psiprobe;

import com.googlecode.psiprobe.model.ApplicationParam;
import com.googlecode.psiprobe.model.ApplicationResource;
import com.googlecode.psiprobe.model.jsp.Item;
import com.googlecode.psiprobe.model.jsp.Summary;

import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.Host;
import org.apache.catalina.Valve;
import org.apache.catalina.Wrapper;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.deploy.ApplicationParameter;
import org.apache.catalina.deploy.ContextResourceLink;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.modeler.Registry;
import org.apache.jasper.EmbeddedServletOptions;
import org.apache.jasper.JasperException;
import org.apache.jasper.JspCompilationContext;
import org.apache.jasper.Options;
import org.apache.jasper.compiler.JspRuntimeContext;
import org.apache.naming.ContextBindings;
import org.springframework.util.ClassUtils;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.naming.NamingException;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;

/**
 * Abstraction layer to implement some functionality, which is common between different container
 * adaptors.
 * 
 * @author Vlad Ilyushchenko
 * @author Mark Lewis
 */
public abstract class AbstractTomcatContainer implements TomcatContainer {

  /** The logger. */
  protected Log logger = LogFactory.getLog(getClass());

  /** The host. */
  protected Host host;
  
  /** The deployer o name. */
  protected ObjectName deployerOName;
  
  /** The mbean server. */
  protected MBeanServer mbeanServer;
  
  @Override
  public void setWrapper(Wrapper wrapper) {
    Valve valve = createValve();
    if (wrapper != null) {
      host = (Host) wrapper.getParent().getParent();
      try {
        deployerOName =
            new ObjectName(host.getParent().getName() + ":type=Deployer,host=" + host.getName());
      } catch (MalformedObjectNameException e) {
        // do nothing here
      }
      host.getPipeline().addValve(valve);
      mbeanServer = Registry.getRegistry(null, null).getMBeanServer();
    } else if (host != null) {
      host.getPipeline().removeValve(valve);
    }
  }
  
  @Override
  public File getAppBase() {
    File base = new File(host.getAppBase());
    if (!base.isAbsolute()) {
      base = new File(System.getProperty("catalina.base"), host.getAppBase());
    }
    return base;
  }

  @Override
  public String getConfigBase() {
    File configBase = new File(System.getProperty("catalina.base"), "conf");
    Container baseHost = null;
    Container thisContainer = host;
    while (thisContainer != null) {
      if (thisContainer instanceof Host) {
        baseHost = thisContainer;
      }
      thisContainer = thisContainer.getParent();
    }
    if (baseHost != null) {
      configBase = new File(configBase, baseHost.getName());
    }
    return configBase.getAbsolutePath();
  }

  @Override
  public String getHostName() {
    return host.getName();
  }

  @Override
  public String getName() {
    return host.getParent().getName();
  }

  @Override
  public List<Context> findContexts() {
    ArrayList<Context> results = new ArrayList<Context>();
    for (Container child : host.findChildren()) {
      if (child instanceof Context) {
        results.add((Context) child);
      }
    }
    return results;
  }

  @Override
  public boolean installContext(String contextName) throws Exception {
    contextName = formatContextName(contextName);
    String contextFilename = formatContextFilename(contextName);
    File contextFile = new File(getConfigBase(), contextFilename + ".xml");
    installContextInternal(contextName, contextFile);
    return findContext(contextName) != null;
  }

  @Override
  public void stop(String name) throws Exception {
    Context ctx = findContext(name);
    if (ctx != null) {
      ctx.stop();
    }
  }

  @Override
  public void start(String name) throws Exception {
    Context ctx = findContext(name);
    if (ctx != null) {
      ctx.start();
    }
  }

  @Override
  public void remove(String name) throws Exception {
    name = formatContextName(name);
    Context ctx = findContext(name);

    if (ctx != null) {

      try {
        stop(name);
      } catch (Throwable e) {
        logger.info("Stopping " + name + " threw this exception:", e);
        // make sure we always re-throw ThreadDeath
        if (e instanceof ThreadDeath) {
          throw (ThreadDeath) e;
        }
      }

      File appDir;
      File docBase = new File(ctx.getDocBase());

      if (!docBase.isAbsolute()) {
        appDir = new File(getAppBase(), ctx.getDocBase());
      } else {
        appDir = docBase;
      }

      logger.debug("Deleting " + appDir.getAbsolutePath());
      Utils.delete(appDir);

      String warFilename = formatContextFilename(name);
      File warFile = new File(getAppBase(), warFilename + ".war");
      logger.debug("Deleting " + warFile.getAbsolutePath());
      Utils.delete(warFile);

      File configFile = getConfigFile(ctx);
      if (configFile != null) {
        logger.debug("Deleting " + configFile.getAbsolutePath());
        Utils.delete(configFile);
      }

      removeInternal(name);
    }
  }

  /**
   * Removes the internal.
   *
   * @param name the name
   * @throws Exception the exception
   */
  private void removeInternal(String name) throws Exception {
    checkChanges(name);
  }

  @Override
  public void installWar(String name, URL url) throws Exception {
    checkChanges(name);
  }

  /**
   * Install context internal.
   *
   * @param name the name
   * @param config the config
   * @throws Exception the exception
   */
  private void installContextInternal(String name, File config) throws Exception {
    checkChanges(name);
  }

  @Override
  public Context findContext(String name) {
    String safeName = formatContextName(name);
    if (safeName == null) {
      return null;
    }
    Context result = findContextInternal(safeName);
    if (result == null && "".equals(safeName)) {
      result = findContextInternal("/");
    }
    return result;
  }

  @Override
  public String formatContextName(String name) {
    if (name == null) {
      return null;
    }
    String result = name.trim();
    if (!result.startsWith("/")) {
      result = "/" + result;
    }
    if ("/".equals(result) || "/ROOT".equals(result)) {
      result = "";
    }
    return result;
  }

  @Override
  public String formatContextFilename(String contextName) {
    if (contextName == null) {
      return null;
    } else if ("".equals(contextName)) {
      return "ROOT";
    } else if (contextName.startsWith("/")) {
      return contextName.substring(1);
    } else {
      return contextName;
    }
  }

  @Override
  public void discardWorkDir(Context context) {
    if (context instanceof StandardContext) {
      StandardContext standardContext = (StandardContext) context;
      logger.info("Discarding " + standardContext.getWorkPath());
      Utils.delete(new File(standardContext.getWorkPath(), "org"));
    } else {
      logger.error("context " + context.getName() + " is not an instance of "
          + context.getClass().getName() + ", expected StandardContext");
    }
  }

  @Override
  public String getServletFileNameForJsp(Context context, String jspName) {
    String servletName = null;

    ServletConfig servletConfig = (ServletConfig) context.findChild("jsp");
    if (servletConfig != null) {
      ServletContext sctx = context.getServletContext();
      Options opt = new EmbeddedServletOptions(servletConfig, sctx);
      JspRuntimeContext jrctx = new JspRuntimeContext(sctx, opt);
      JspCompilationContext jcctx =
          createJspCompilationContext(jspName, opt, sctx, jrctx, null);
      servletName = jcctx.getServletJavaFileName();
    } else {
      logger.error("Context " + context.getName() + " does not have \"jsp\" servlet");
    }
    return servletName;
  }

  @Override
  public void recompileJsps(Context context, Summary summary, List<String> names) {
    ServletConfig servletConfig = (ServletConfig) context.findChild("jsp");
    if (servletConfig != null) {
      if (summary != null) {
        synchronized (servletConfig) {
          ServletContext sctx = context.getServletContext();
          Options opt = new EmbeddedServletOptions(servletConfig, sctx);

          JspRuntimeContext jrctx = new JspRuntimeContext(sctx, opt);
          try {
            /*
             * we need to pass context classloader here, so the jsps can reference /WEB-INF/classes
             * and /WEB-INF/lib. JspCompilationContext would only take URLClassLoader, so we fake it
             */
            URLClassLoader classLoader =
                new URLClassLoader(new URL[] {}, context.getLoader().getClassLoader());
            for (String name : names) {
              long time = System.currentTimeMillis();
              JspCompilationContext jcctx =
                  createJspCompilationContext(name, opt, sctx, jrctx, classLoader);
              ClassLoader prevCl = ClassUtils.overrideThreadContextClassLoader(classLoader);
              try {
                Item item = (Item) summary.getItems().get(name);
                if (item != null) {
                  try {
                    org.apache.jasper.compiler.Compiler compiler = jcctx.createCompiler();
                    compiler.compile();
                    item.setState(Item.STATE_READY);
                    item.setException(null);
                    logger.info("Compiled " + name + ": OK");
                  } catch (Exception e) {
                    item.setState(Item.STATE_FAILED);
                    item.setException(e);
                    logger.info("Compiled " + name + ": FAILED", e);
                  }
                  item.setCompileTime(System.currentTimeMillis() - time);
                } else {
                  logger.error(name + " is not on the summary list, ignored");
                }
              } finally {
                ClassUtils.overrideThreadContextClassLoader(prevCl);
              }
            }
          } finally {
            jrctx.destroy();
          }
        }
      } else {
        logger.error("summary is null for " + context.getName() + ", request ignored");
      }
    } else {
      logger.error("Context " + context.getName() + " does not have \"jsp\" servlet");
    }
  }

  @Override
  public void listContextJsps(Context context, Summary summary, boolean compile) {
    ServletConfig servletConfig = (ServletConfig) context.findChild("jsp");
    if (servletConfig != null) {
      synchronized (servletConfig) {
        ServletContext sctx = context.getServletContext();
        Options opt = new EmbeddedServletOptions(servletConfig, sctx);

        JspRuntimeContext jrctx = new JspRuntimeContext(sctx, opt);
        try {
          if (summary.getItems() == null) {
            summary.setItems(new HashMap<String, Item>());
          }

          /*
           * mark all items as missing
           */
          for (Item item : summary.getItems().values()) {
            item.setMissing(true);
          }

          /*
           * we need to pass context classloader here, so the jsps can reference /WEB-INF/classes
           * and /WEB-INF/lib. JspCompilationContext would only take URLClassLoader, so we fake it
           */
          URLClassLoader urlcl = new URLClassLoader(
              new URL[] {}, context.getLoader().getClassLoader());
          
          compileItem("/", opt, context, jrctx, summary, urlcl, 0, compile);
        } finally {
          jrctx.destroy();
        }
      }

      //
      // delete "missing" items by keeping "not missing" ones
      //
      Map<String, Item> hashMap = new HashMap<String, Item>();
      for (String key : summary.getItems().keySet()) {
        Item item = summary.getItems().get(key);
        if (!item.isMissing()) {
          hashMap.put(key, item);
        }
      }

      summary.setItems(hashMap);
    } else {
      logger.error("Context " + context.getName() + " does not have \"jsp\" servlet");
    }
  }

  @Override
  public org.apache.juli.logging.Log getLogger(Context context) {
    return context.getLogger();
  }

  @Override
  public boolean getAvailable(Context context) {
    return context.getState().isAvailable();
  }

  @Override
  public File getConfigFile(Context context) {
    URL configUrl = context.getConfigFile();
    if (configUrl != null) {
      try {
        URI configUri = configUrl.toURI();
        if ("file".equals(configUri.getScheme())) {
          return new File(configUri.getPath());
        }
      } catch (Exception ex) {
        logger.error("Could not convert URL to URI: " + configUrl, ex);
      }
    }
    return null;
  }

  @Override
  public List<ApplicationParam> getApplicationInitParams(Context context) {
    /*
     * We'll try to determine if a parameter value comes from a deployment descriptor or a context
     * descriptor.
     *
     * Assumption: context.findParameter() returns only values of parameters that are declared in a
     * deployment descriptor.
     *
     * If a parameter is declared in a context descriptor with override=false and redeclared in a
     * deployment descriptor, context.findParameter() still returns its value, even though the value
     * is taken from a context descriptor.
     *
     * context.findApplicationParameters() returns all parameters that are declared in a context
     * descriptor regardless of whether they are overridden in a deployment descriptor or not or
     * not.
     */
    /*
     * creating a set of parameter names that are declared in a context descriptor and can not be
     * ovevridden in a deployment descriptor.
     */
    Set<String> nonOverridableParams = new HashSet<String>();
    for (ApplicationParameter appParam : context.findApplicationParameters()) {
      if (appParam != null && !appParam.getOverride()) {
        nonOverridableParams.add(appParam.getName());
      }
    }
    List<ApplicationParam> initParams = new ArrayList<ApplicationParam>();
    ServletContext servletCtx = context.getServletContext();
    for (Enumeration e = servletCtx.getInitParameterNames(); e.hasMoreElements();) {
      String paramName = (String) e.nextElement();
      ApplicationParam param = new ApplicationParam();
      param.setName(paramName);
      param.setValue(servletCtx.getInitParameter(paramName));
      /*
       * if the parameter is declared in a deployment descriptor and it is not declared in a context
       * descriptor with override=false, the value comes from the deployment descriptor
       */
      param.setFromDeplDescr(context.findParameter(paramName) != null
          && !nonOverridableParams.contains(paramName));
      initParams.add(param);
    }
    return initParams;
  }

  @Override
  public void addContextResourceLink(Context context, List<ApplicationResource> resourceList,
      boolean contextBound) {
    for (ContextResourceLink link : context.getNamingResources().findResourceLinks()) {
      ApplicationResource resource = new ApplicationResource();
      logger.debug("reading resourceLink: " + link.getName());
      resource.setApplicationName(context.getName());
      resource.setName(link.getName());
      resource.setType(link.getType());
      resource.setLinkTo(link.getGlobal());
      // lookupResource(resource, contextBound, false);
      resourceList.add(resource);
    }
  }

  @Override
  public void bindToContext(Context context) throws NamingException {
    changeContextBinding(context, true);
  }

  @Override
  public void unbindFromContext(Context context) throws NamingException {
    changeContextBinding(context, false);
  }
  
  /**
   * Change context binding.
   *
   * @param context the context
   * @param bind the bind
   * @throws NamingException the naming exception
   */
  private void changeContextBinding(Context context, boolean bind) throws NamingException {
    Object token = getNamingToken(context);
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    if (bind) {
      ContextBindings.bindClassLoader(context, token, loader);
    } else {
      ContextBindings.unbindClassLoader(context, token, loader);
    }
  }

  /**
   * Lists and optionally compiles a directory recursively.
   *
   * @param jspName name of JSP file or directory to be listed and compiled.
   * @param opt the JSP compiler options
   * @param ctx the context
   * @param jrctx the runtime context used to create the compilation context
   * @param summary the summary in which the output is stored
   * @param classLoader the classloader used by the compiler
   * @param level the depth in the tree at which the item was encountered
   * @param compile whether or not to compile the item or just to check whether it's out of date
   */
  protected void compileItem(String jspName, Options opt, Context ctx, JspRuntimeContext jrctx,
      Summary summary, URLClassLoader classLoader, int level, boolean compile) {
    ServletContext sctx = ctx.getServletContext();
    Set<String> paths = sctx.getResourcePaths(jspName);

    if (paths != null) {
      for (String name : paths) {
        boolean isJsp = false;

        try {
          isJsp = name.endsWith(".jsp") || name.endsWith(".jspx")
              || opt.getJspConfig().isJspPage(name);
        } catch (JasperException e) {
          logger.info("isJspPage() thrown an error for " + name, e);
        }

        if (isJsp) {
          JspCompilationContext jcctx =
              createJspCompilationContext(name, opt, sctx, jrctx, classLoader);
          ClassLoader prevCl = ClassUtils.overrideThreadContextClassLoader(classLoader);
          try {
            Item item = (Item) summary.getItems().get(name);

            if (item == null) {
              item = new Item();
              item.setName(name);
            }

            item.setLevel(level);
            item.setCompileTime(-1);

            Long[] objects = this.getResourceAttributes(name, ctx);
            item.setSize(objects[0]);
            item.setLastModified(objects[1]);

            long time = System.currentTimeMillis();
            try {
              org.apache.jasper.compiler.Compiler compiler = jcctx.createCompiler();
              if (compile) {
                compiler.compile();
                item.setState(Item.STATE_READY);
                item.setException(null);
              } else {
                if (!compiler.isOutDated()) {
                  item.setState(Item.STATE_READY);
                  item.setException(null);
                } else if (item.getState() != Item.STATE_FAILED) {
                  item.setState(Item.STATE_OOD);
                  item.setException(null);
                }
              }
              logger.info("Compiled " + name + ": OK");
            } catch (Exception e) {
              item.setState(Item.STATE_FAILED);
              item.setException(e);
              logger.info("Compiled " + name + ": FAILED", e);
            }
            if (compile) {
              item.setCompileTime(System.currentTimeMillis() - time);
            }
            item.setMissing(false);
            summary.getItems().put(name, item);
          } finally {
            ClassUtils.overrideThreadContextClassLoader(prevCl);
          }
        } else {
          compileItem(name, opt, ctx, jrctx, summary, classLoader, level + 1, compile);
        }
      }
    } else {
      logger.debug("getResourcePaths() is null for " + jspName + ". Empty dir? Or Tomcat bug?");
    }
  }

  /**
   * Find context internal.
   *
   * @param name the context name
   * @return the context
   */
  protected Context findContextInternal(String name) {
    return (Context) host.findChild(name);
  }

  /**
   * Check changes.
   *
   * @param name the name
   * @throws Exception the exception
   */
  protected void checkChanges(String name) throws Exception {
    Boolean result =
        (Boolean) mbeanServer.invoke(deployerOName, "isServiced", new String[] {name},
            new String[] {"java.lang.String"});
    if (!result) {
      mbeanServer.invoke(deployerOName, "addServiced", new String[] {name},
          new String[] {"java.lang.String"});
      try {
        mbeanServer.invoke(deployerOName, "check", new String[] {name},
            new String[] {"java.lang.String"});
      } finally {
        mbeanServer.invoke(deployerOName, "removeServiced", new String[] {name},
            new String[] {"java.lang.String"});
      }
    }
  }

  /**
   * Returns the security token required to bind to a naming context.
   *
   * @param context the catalina context
   *
   * @return the security token for use with <code>ContextBindings</code>
   */
  protected abstract Object getNamingToken(Context context);

  /**
   * Creates the jsp compilation context.
   *
   * @param name the name
   * @param opt the opt
   * @param sctx the sctx
   * @param jrctx the jrctx
   * @param classLoader the class loader
   * @return the jsp compilation context
   */
  protected abstract JspCompilationContext createJspCompilationContext(String name, Options opt,
      ServletContext sctx, JspRuntimeContext jrctx, ClassLoader classLoader);

  protected abstract Valve createValve();

}
