/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.greenpole.notifier.authoriser;

import java.io.File;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import org.greenpole.entity.notification.NotificationWrapper;
import org.greenpole.entity.notification.SenderReceiverType;
import org.greenpole.hibernate.query.ClientCompanyComponentQuery;
import org.greenpole.hibernate.query.GeneralComponentQuery;
import org.greenpole.hibernate.query.factory.ComponentQueryFactory;
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

    public AuthoriserNotifier(NotificationWrapper wrapper) {
        notify(wrapper);
    }

    @Override
    public void run() {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }
    
    private void notify(NotificationWrapper wrapper) {
        try {
            //set extra information
            wrapper.setFromType(SenderReceiverType.Internal.toString());
            wrapper.setToType(SenderReceiverType.Internal.toString());
            wrapper.setAttendedTo(false);
            
            File file = new File("/etc/greenpole/notifications/authorisations/" + wrapper.getCode() + ".xml");
            file.getParentFile().mkdirs();
            
            JAXBContext jaxbContext = JAXBContext.newInstance(NotificationWrapper.class);
            Marshaller jaxbMarshaller = jaxbContext.createMarshaller();
            jaxbMarshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
            jaxbMarshaller.marshal(wrapper, file);
            logger.info("notification file created - [{}.xml]", wrapper.getCode());
            
            //register notification in database
            cq.createNotification(wrapper);
        } catch (JAXBException ex) {
            logger.info("an error occured while creating the notification file - [{}.xml]. See error log ", wrapper.getCode());
            logger.error("a JAXBException was thrown by the authoriser notifier on creation of notification - [{}]", wrapper.getCode(), ex);
        }
    }
}
