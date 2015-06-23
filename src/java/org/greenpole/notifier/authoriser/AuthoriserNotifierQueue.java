/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.greenpole.notifier.authoriser;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import javax.ejb.ActivationConfigProperty;
import javax.ejb.MessageDriven;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.MessageProducer;
import javax.jms.ObjectMessage;
import javax.jms.Queue;
import javax.jms.QueueConnection;
import javax.jms.QueueConnectionFactory;
import javax.jms.QueueSession;
import javax.jms.Session;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import org.greenpole.entity.exception.ConfigNotFoundException;
import org.greenpole.entity.notification.NotificationWrapper;
import org.greenpole.entity.response.Response;
import org.greenpole.util.properties.NotifierProperties;
import org.greenpole.util.properties.ThreadPoolProperties;
import org.greenpole.util.threadfactory.GreenpoleNotifierFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Akinwale.Agbaje
 * Collects feeds authorisation requests to {@link AuthoriserNotifier}
 * @see AuthoriserNotifier
 */
@MessageDriven(mappedName = "jms/AuthorisationQueue", activationConfig = {
    @ActivationConfigProperty(propertyName = "destinationType", propertyValue = "javax.jms.Queue")
})
public class AuthoriserNotifierQueue implements MessageListener {
    private static final Logger logger = LoggerFactory.getLogger(AuthoriserNotifierQueue.class);
    private final ThreadPoolProperties threadPoolProp = ThreadPoolProperties.getInstance();
    private final NotifierProperties notifierProp = NotifierProperties.getInstance();
    private final ExecutorService service;
    private Context context;
    private QueueConnectionFactory qconFactory;
    private QueueConnection qcon;
    private QueueSession qsession;
    private MessageProducer producer;
    private int POOL_SIZE;
    
    public AuthoriserNotifierQueue() {
        try {
            POOL_SIZE = Integer.parseInt(threadPoolProp.getAuthoriserNotifierQueuePoolSize());
        } catch (Exception ex) {
            logger.info("Invalid property for pool size - see error log. Setting default size to 15");
            logger.error("Error assigning property value to pool size", ex);
            POOL_SIZE = 15;
        }
        
        service = Executors.newFixedThreadPool(POOL_SIZE, new GreenpoleNotifierFactory("AuthoriserNotifierQueue-AuthoriserNotifier"));
        
        try {
            initialiseQueueFactory(notifierProp.getNotifierQueueFactory());
            prepareResponseQueue();
        } catch (NamingException | ConfigNotFoundException | IOException | JMSException ex) {
            logger.info("Error thrown in QueueSender initialisation-preparation process. See error log");
            logger.error("An error(s) was thrown in the Queue", ex);
        }
    }
    
    @Override
    public void onMessage(Message message) {
        logger.info("Received message from external source. Will process now...");
        try {
            if (((ObjectMessage) message).getObject() instanceof NotificationWrapper) {
                //send response
                if (message.getJMSReplyTo() != null) {
                    logger.info("sending response");
                    respondToSenderPositive(message);
                }
                
                NotificationWrapper wrapper = (NotificationWrapper) ((ObjectMessage) message).getObject();
                logger.info("received notification - [{}]", wrapper.getCode());
                service.execute(new AuthoriserNotifier(wrapper)); //start authoriser notifier thread
                
                //closeConnections();
            } else {
                logger.info("Message received is not an instance of NotificationWrapper");
                if (message.getJMSReplyTo() != null) {
                    respondToSenderNegative(message);
                }
                //closeConnections();
            }
        } catch (JMSException ex) {
            logger.info("error thrown while receiving message - see error log");
            logger.error("error thrown while receiving message", ex);
        } catch (Exception ex) {
            logger.info("error thrown while receiving message - see error log");
            logger.error("error thrown while receiving message", ex);
        }
    }
    
    /**
     * Initialises queue factory.
     * @param queueConnectionFactory the name of the queue factory to initialise
     * @throws NamingException property key-name is incorrect, or incorrect queue factory name
     * @throws ConfigNotFoundException configuration file not found
     * @throws IOException error loading file into properties
     */
    private void initialiseQueueFactory(String queueConnectionFactory) throws NamingException, ConfigNotFoundException, IOException {
        logger.info("initialising queue factory - [{}]", queueConnectionFactory);
        context = AuthoriserNotifierQueue.getInitialContext();
        qconFactory = (QueueConnectionFactory) context.lookup(queueConnectionFactory);
    }
    
    /**
     * Prepares the response queue.
     * @throws JMSException error creating queue session
     * @throws NamingException incorrect queue name
     */
    private void prepareResponseQueue() throws JMSException, NamingException {
        logger.info("preparing response queue");
        qcon = qconFactory.createQueueConnection();
        qsession = qcon.createQueueSession(false, Session.AUTO_ACKNOWLEDGE);
    }
    
    private void respondToSenderPositive(Message message) throws JMSException {
        Response resp = new Response();
        resp.setRetn(0);
        resp.setDesc("Notification submitted to queue.");
        producer = qsession.createProducer(message.getJMSReplyTo());
        producer.send(qsession.createObjectMessage(resp));
    }
    
    private void respondToSenderNegative(Message message) throws JMSException {
        Response resp = new Response();
        resp.setRetn(100);
        resp.setDesc("Message not a legal Notification Wrapper");
        producer = qsession.createProducer(message.getJMSReplyTo());
        producer.send(qsession.createObjectMessage(resp));
    }
    
    private void closeConnections() throws JMSException {
        producer.close();
        qsession.close();
        qcon.close();
    }
    
    /**
     * Loads queue configuration into initial context.
     * @return initial context
     * @throws NamingException property key-name, or queue factory name, or queue name is incorrect
     * @throws ConfigNotFoundException configuration file not found
     * @throws IOException error loading file into properties
     */
    private static Context getInitialContext() throws NamingException, ConfigNotFoundException, IOException {
        String config_file = "queue_config.properties";
        Properties properties = new Properties();
        InputStream input = AuthoriserNotifierQueue.class.getClassLoader().getResourceAsStream(config_file);
        logger.info("Loading configuration file - {}", config_file);
        
        if (input == null) {
            logger.info("Failure to load configuration file - {}", config_file);
            throw new ConfigNotFoundException("queue_config.properties file missing from classpath");
        }
        
        properties.load(input);
        input.close();
        logger.info("Loaded configuration file - {}", config_file);
        
        return new InitialContext(properties);
    }
    
}
