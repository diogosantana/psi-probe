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

package psiprobe.tools.logging.log4j;

import org.apache.commons.beanutils.MethodUtils;

import psiprobe.tools.logging.DefaultAccessor;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * The Class Log4JManagerAccessor.
 *
 * @author Vlad Ilyushchenko
 * @author Mark Lewis
 */
public class Log4JManagerAccessor extends DefaultAccessor {

  /**
   * Instantiates a new log4 j manager accessor.
   *
   * @param cl the cl
   * @throws ClassNotFoundException the class not found exception
   */
  public Log4JManagerAccessor(ClassLoader cl) throws ClassNotFoundException {
    Class clazz = cl.loadClass("org.apache.log4j.LogManager");
    Method exists = MethodUtils.getAccessibleMethod(clazz, "exists", new Class[] {String.class});
    if (exists == null) {
      throw new RuntimeException("The LogManager is part of the slf4j bridge.");
    }
    setTarget(clazz);
  }

  /**
   * Gets the root logger.
   *
   * @return the root logger
   */
  public Log4JLoggerAccessor getRootLogger() {
    try {
      Class clazz = (Class) getTarget();
      Method getRootLogger = MethodUtils
          .getAccessibleMethod(clazz, "getRootLogger", new Class[0]);
      
      Object logger = getRootLogger.invoke(null);
      if (logger == null) {
        throw new NullPointerException(getTarget().getClass().getName()
            + "#getRootLogger() returned null");
      }
      Log4JLoggerAccessor accessor = new Log4JLoggerAccessor();
      accessor.setTarget(logger);
      accessor.setApplication(getApplication());
      return accessor;
    } catch (Exception e) {
      logger.error(getTarget().getClass().getName() + "#getRootLogger() failed", e);
    }
    return null;
  }

  /**
   * Gets the logger.
   *
   * @param name the name
   * @return the logger
   */
  public Log4JLoggerAccessor getLogger(String name) {
    try {
      Class clazz = (Class) getTarget();
      Method getLogger = MethodUtils
          .getAccessibleMethod(clazz, "getLogger", new Class[] {String.class});
      
      Object logger = getLogger.invoke(null, new Object[] {name});
      if (logger == null) {
        throw new NullPointerException(getTarget().getClass().getName() + "#getLogger(\"" + name
            + "\") returned null");
      }
      Log4JLoggerAccessor accessor = new Log4JLoggerAccessor();
      accessor.setTarget(logger);
      accessor.setApplication(getApplication());
      return accessor;
    } catch (Exception e) {
      logger.error(getTarget().getClass().getName() + "#getLogger(\"" + name + "\") failed", e);
    }
    return null;
  }

  /**
   * Gets the appenders.
   *
   * @return the appenders
   */
  public List<Log4JAppenderAccessor> getAppenders() {
    List<Log4JAppenderAccessor> appenders = new ArrayList<Log4JAppenderAccessor>();
    try {
      appenders.addAll(getRootLogger().getAppenders());

      Class clazz = (Class) getTarget();
      Method getCurrentLoggers = MethodUtils
          .getAccessibleMethod(clazz, "getCurrentLoggers", new Class[0]);
      
      Enumeration currentLoggers = (Enumeration) getCurrentLoggers.invoke(null);
      while (currentLoggers.hasMoreElements()) {
        Log4JLoggerAccessor accessor = new Log4JLoggerAccessor();
        accessor.setTarget(currentLoggers.nextElement());
        accessor.setApplication(getApplication());

        appenders.addAll(accessor.getAppenders());
      }
    } catch (Exception e) {
      logger.error(getTarget().getClass().getName() + "#getCurrentLoggers() failed", e);
    }
    return appenders;
  }

}