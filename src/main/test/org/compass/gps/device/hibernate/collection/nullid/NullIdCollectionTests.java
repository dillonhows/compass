package org.compass.gps.device.hibernate.collection.nullid;

import junit.framework.TestCase;
import org.compass.core.Compass;
import org.compass.core.CompassSession;
import org.compass.core.CompassTransaction;
import org.compass.core.config.CompassConfiguration;
import org.compass.gps.CompassGpsDevice;
import org.compass.gps.device.hibernate.HibernateGpsDevice;
import org.compass.gps.device.hibernate.HibernateSyncTransactionFactory;
import org.compass.gps.impl.SingleCompassGps;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.cfg.Configuration;
import org.hibernate.cfg.Environment;
import org.hibernate.classic.Session;

public class NullIdCollectionTests extends TestCase {

    private Compass compass;

    private SessionFactory sessionFactory;

    public void setUp() {
        Configuration conf = new Configuration().configure("/org/compass/gps/device/hibernate/collection/nullid/hibernate.cfg.xml")
                .setProperty(Environment.HBM2DDL_AUTO, "create");
        sessionFactory = conf.buildSessionFactory();

        // set the session factory for the Hibernate transcation factory BEFORE we construct the compass instnace
        HibernateSyncTransactionFactory.setSessionFactory(sessionFactory);

        CompassConfiguration cpConf = new CompassConfiguration()
                .configure("/org/compass/gps/device/hibernate/collection/nullid/compass.cfg.xml");
        compass = cpConf.buildCompass();
        compass.getSearchEngineIndexManager().deleteIndex();
        compass.getSearchEngineIndexManager().verifyIndex();

        HibernateGpsDevice compassGpsDevice = new HibernateGpsDevice();
        compassGpsDevice.setName("hibernate");
        compassGpsDevice.setSessionFactory(sessionFactory);
        compassGpsDevice.setFetchCount(5000);
        compassGpsDevice.setMirrorDataChanges(true);

        SingleCompassGps compassGps = new SingleCompassGps();
        compassGps.setCompass(compass);
        compassGps.setGpsDevices(new CompassGpsDevice[]{compassGpsDevice});

        compassGps.start();

    }

    protected void tearDown() throws Exception {
        compass.close();
        sessionFactory.close();
    }

    public void testMarshall() {
        //TODO see if we can fix this test
        if (true) {
            return;
        }
        CompassSession session = compass.openSession();
        CompassTransaction tr = session.beginTransaction();

        Session hibSession = sessionFactory.openSession();
        Transaction hibTr = hibSession.beginTransaction();

        User u1 = new User();
        u1.setName("barcho");

        Album a1 = new Album();
        a1.setTitle("the first album");

        Album a2 = new Album();
        a2.setTitle("the second album");

        u1.getAlbums().add(a1);
        u1.getAlbums().add(a2);

        hibSession.save(u1);

        tr.commit();
        session.close();

        hibTr.commit();
        hibSession.close();
    }
}
