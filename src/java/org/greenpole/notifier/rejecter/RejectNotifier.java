/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.greenpole.notifier.rejecter;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import org.greenpole.entity.notification.NotificationWrapper;
import org.greenpole.entity.notification.SenderReceiverType;
import org.greenpole.hibernate.entity.Notification;
import org.greenpole.hibernate.query.GeneralComponentQuery;
import org.greenpole.hibernate.query.factory.ComponentQueryFactory;
import org.greenpole.util.Manipulator;
import org.greenpole.util.email.EmailClient;
import org.greenpole.util.email.TemplateReader;
import org.greenpole.util.properties.EmailProperties;
import org.greenpole.util.properties.GreenpoleProperties;
import org.greenpole.util.properties.NotificationProperties;
import org.greenpole.util.properties.ThreadPoolProperties;
import org.greenpole.util.threadfactory.GreenpoleNotifierFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Akinwale Agbaje
 * Rejects notifications and stores them in the database and file
 */
public class RejectNotifier implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(RejectNotifier.class);
    private final GeneralComponentQuery gq = ComponentQueryFactory.getGeneralComponentQuery();
    private final ThreadPoolProperties threadPoolProp = ThreadPoolProperties.getInstance();
    private final EmailProperties emailProp = EmailProperties.getInstance();
    private final GreenpoleProperties greenProp = GreenpoleProperties.getInstance();
    private final NotificationProperties notificationProp = NotificationProperties.getInstance();
    private final ExecutorService service;
    private final NotificationWrapper wrapper;
    private int POOL_SIZE = 20;

    public RejectNotifier(NotificationWrapper wrapper) {
        try {
            POOL_SIZE = Integer.parseInt(threadPoolProp.getAuthoriserNotifierPoolSize());
        } catch (Exception ex) {
            logger.info("Invalid property for pool size - see error log. Setting default size to 15");
            logger.error("Error assigning property value to pool size", ex);
            POOL_SIZE = 15;
        }
        
        service = Executors.newFixedThreadPool(POOL_SIZE, new GreenpoleNotifierFactory("RejectNotifier-EmailClient"));
        this.wrapper = wrapper;
    }

    @Override
    public void run() {
        rejectNotification();
    }
    
    private void rejectNotification() {
        try {
            logger.info("setting extra information in wrapper");
            //set extra information
            SimpleDateFormat formatter = new SimpleDateFormat(greenProp.getDateFormat());
            wrapper.setAttendedTo(true);
            wrapper.setRejected(true);
            wrapper.setAttendedDate(formatter.format(new Date()));
            
            org.greenpole.util.Notification notUtil = new org.greenpole.util.Notification();
            logger.info("persisting notfication file");
            notUtil.persistNotificationFile(notificationProp.getNotificationLocation(), wrapper.getCode(), wrapper);
            
            //register notification in database
            notUtil.markRejected(wrapper.getCode());
            logger.info("notification file registered in database");
            
            //send email notification
            Manipulator manipulate = new Manipulator();
            String[] authoriser_names = manipulate.separateNameFromEmail(wrapper.getTo());
            String[] requester_names = manipulate.separateNameFromEmail(wrapper.getFrom());
            
            String subject = "Authorisation rejected";
            String templatePath = emailProp.getAuthorisationMailTemplate();
            
            String template = TemplateReader.getTemplateContent(templatePath);
            String to_person = authoriser_names[0] + " " + authoriser_names[1];
            String from_person = requester_names[0] + " " + requester_names[1];
            String body_main = "Your authorisation request to " + to_person + " has been rejected.<br>"
                    + "<b>Reasons for rejection:<b><br>"
                    + wrapper.getRejectionReason() + "<br>"
                    + "You can log into the system and correct any errors or accept the rejection.<br><br>"
                    + "Thank you.";
            
            EmailClient mailer = new EmailClient(emailProp.getMailSender(), wrapper.getFrom(), subject, from_person, body_main, template);
            service.execute(mailer);
        } catch (JAXBException ex) {
            logger.info("an error occured while persisting the notification file - [{}.xml]. See error log - ", wrapper.getCode());
            logger.error("a JAXBException was thrown by the reject notifier on persistence of notification - [{}]", wrapper.getCode(), ex);
        } catch (Exception ex) {
            logger.info("an error occured while persisting the notification file - [{}.xml]. See error log - ", wrapper.getCode());
            logger.error("a Exception was thrown by the reject notifier on persistence of notification - [{}]", wrapper.getCode(), ex);
        }
    }
    
}
