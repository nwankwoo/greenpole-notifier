/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.greenpole.notifier.authoriser;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import org.greenpole.entity.notification.NotificationWrapper;
import org.greenpole.entity.notification.SenderReceiverType;
import org.greenpole.hibernate.entity.Notification;
import org.greenpole.hibernate.query.ClientCompanyComponentQuery;
import org.greenpole.hibernate.query.GeneralComponentQuery;
import org.greenpole.hibernate.query.factory.ComponentQueryFactory;
import org.greenpole.util.email.EmailClient;
import org.greenpole.util.properties.EmailProperties;
import org.greenpole.util.properties.NotificationProperties;
import org.greenpole.util.properties.ThreadPoolProperties;
import org.greenpole.util.threadfactory.GreenpoleNotifierFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Akinwale.Agbaje
 * Creates notifications on the database for authorisation requests.
 */
public class AuthoriserNotifier implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(AuthoriserNotifier.class);
    private final GeneralComponentQuery cq = ComponentQueryFactory.getGeneralComponentQuery();
    private final ThreadPoolProperties threadPoolProp = new ThreadPoolProperties(AuthoriserNotifier.class);
    private final EmailProperties emailProp = new EmailProperties(AuthoriserNotifier.class);
    private final NotificationProperties notificationProp = new NotificationProperties(AuthoriserNotifier.class);
    private final ExecutorService service;
    private final NotificationWrapper wrapper;
    private int POOL_SIZE;

    public AuthoriserNotifier(NotificationWrapper wrapper) {
        
        try {
            POOL_SIZE = Integer.parseInt(threadPoolProp.getAuthoriserNotifierPoolSize());
        } catch (Exception ex) {
            logger.info("Invalid property for pool size - see error log. Setting default size to 15");
            logger.error("Error assigning property value to pool size", ex);
            POOL_SIZE = 15;
        }
        
        service = Executors.newFixedThreadPool(POOL_SIZE, new GreenpoleNotifierFactory("AuthoriserNotifier-EmailClient"));
        this.wrapper = wrapper;
        createNotification();
    }

    @Override
    public void run() {
        createNotification();
    }
    
    private void createNotification() {
        try {
            //set extra information
            wrapper.setFromType(SenderReceiverType.Internal.toString());
            wrapper.setToType(SenderReceiverType.Internal.toString());
            wrapper.setAttendedTo(false);
            
            File file = new File(notificationProp.getNotificationLocation() + wrapper.getCode() + ".xml");
            file.getParentFile().mkdirs();
            
            JAXBContext jaxbContext = JAXBContext.newInstance(NotificationWrapper.class);
            Marshaller jaxbMarshaller = jaxbContext.createMarshaller();
            jaxbMarshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
            jaxbMarshaller.marshal(wrapper, file);
            logger.info("notification file created - [{}.xml]", wrapper.getCode());
            
            //register notification in database
            Notification notification = new Notification(wrapper.getCode(), 
                wrapper.getDescription(),wrapper.getFrom(), wrapper.getTo(),
                wrapper.getFromType(), wrapper.getToType(), wrapper.isAttendedTo());
            cq.createNotification(notification);
            
            //send email notification
            String subject = "Authorisation requested";
            String templatePath = emailProp.getMailTemplate();
            EmailClient mailer = new EmailClient(wrapper.getFrom(), wrapper.getTo(), subject, templatePath);
            service.execute(mailer);
        } catch (JAXBException ex) {
            logger.info("an error occured while creating the notification file - [{}.xml]. See error log ", wrapper.getCode());
            logger.error("a JAXBException was thrown by the authoriser notifier on creation of notification - [{}]", wrapper.getCode(), ex);
        }
    }
}
