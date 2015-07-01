/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.greenpole.notifier.textsender;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.ejb.ActivationConfigProperty;
import javax.ejb.MessageDriven;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageListener;
import javax.jms.MessageProducer;
import javax.jms.ObjectMessage;
import javax.jms.QueueConnection;
import javax.jms.QueueConnectionFactory;
import javax.jms.QueueSession;
import javax.jms.Session;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import org.greenpole.entity.exception.ConfigNotFoundException;
import org.greenpole.entity.response.Response;
import org.greenpole.entity.sms.TextSend;
import org.greenpole.util.properties.NotifierProperties;
import org.greenpole.util.properties.QueueConfigProperties;
import org.greenpole.util.properties.ThreadPoolProperties;
import org.greenpole.util.threadfactory.GreenpoleNotifierFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Akinwale Agbaje
 */
@MessageDriven(mappedName = "jms/TextMessageQueue", activationConfig = {
    @ActivationConfigProperty(propertyName = "destinationType", propertyValue = "javax.jms.Queue")
})
public class TextNotifierQueue implements MessageListener {
    private static final Logger logger = LoggerFactory.getLogger(TextNotifierQueue.class);
    private final ThreadPoolProperties threadPoolProp = ThreadPoolProperties.getInstance();
    private final NotifierProperties notifierProp = NotifierProperties.getInstance();
    private static final QueueConfigProperties queueConfigProp = QueueConfigProperties.getInstance();
    private final ExecutorService service;
    private Context context;
    private QueueConnectionFactory qconFactory;
    private QueueConnection qcon;
    private QueueSession qsession;
    private MessageProducer producer;
    private int POOL_SIZE;
    
    public TextNotifierQueue() {
        try {
            POOL_SIZE = Integer.parseInt(threadPoolProp.getTextNotifierQueuePoolSize());
        } catch (Exception ex) {
            logger.info("Invalid property for pool size - see error log. Setting default size to 15");
            logger.error("Error assigning property value to pool size", ex);
            POOL_SIZE = 15;
        }
        
        service = Executors.newFixedThreadPool(POOL_SIZE, new GreenpoleNotifierFactory("TextNotifierQueue-TextNotifier"));
        
        try {
            initialiseQueueFactory(notifierProp.getNotifierQueueFactory());
            prepareResponseQueue();
        } catch (NamingException | ConfigNotFoundException | IOException | JMSException ex) {
            logger.info("Error thrown in Text notifier queue initialisation-preparation process. See error log");
            logger.error("An error(s) was thrown in the Queue", ex);
        }
    }
    
    @Override
    public void onMessage(Message message) {
        logger.info("Received message from external source. Will process now...");
        try {
            if (((ObjectMessage) message).getObject() instanceof TextSend) {
                //send response
                if (message.getJMSReplyTo() != null) {
                    logger.info("sending response");
                    respondToSenderPositive(message);
                }
                
                TextSend toSend = (TextSend) ((ObjectMessage) message).getObject();
                logger.info("received text - [{}]", toSend.getMessage_id());
                logger.info("text messaging function setting (\"false\" - for disabled) - [{}]", toSend.getMessage_id());
                if (toSend.isAllowText())
                    service.execute(new TextNotifier(toSend)); //start authoriser notifier thread
                //closeConnections();
            } else {
                logger.info("Message received is not an instance of TextSend");
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
        context = TextNotifierQueue.getInitialContext();
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
        TextSend toSend = (TextSend) ((ObjectMessage) message).getObject();
        Response resp = new Response();
        resp.setRetn(0);
        if (toSend.isAllowText())
            resp.setDesc("Text submitted to queue.");
        else
            resp.setDesc("Text submitted to queue but not processed, because text messaging function has been disabled.");
        producer = qsession.createProducer(message.getJMSReplyTo());
        producer.send(qsession.createObjectMessage(resp));
    }
    
    private void respondToSenderNegative(Message message) throws JMSException {
        Response resp = new Response();
        resp.setRetn(100);
        resp.setDesc("Message not a legal text sender object");
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
        return new InitialContext(queueConfigProp);
    }
}
