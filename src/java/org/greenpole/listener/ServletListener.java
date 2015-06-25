/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.greenpole.listener;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Web application lifecycle listener.
 *
 * @author Akinwale Agbaje
 */
@WebListener()
public class ServletListener implements ServletContextListener {
    private static final Logger logger = LoggerFactory.getLogger(ServletListener.class);

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        logger.info("greenpole-notifier started");
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        logger.info("greenpole-notifier destroyed");
    }
}
