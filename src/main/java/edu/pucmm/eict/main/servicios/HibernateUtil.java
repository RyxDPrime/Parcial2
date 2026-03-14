package edu.pucmm.eict.main.servicios;

import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;

public class HibernateUtil {

    private static SessionFactory sessionFactory;

    private HibernateUtil() {
    }

    public static synchronized SessionFactory getSessionFactory() {
        if (sessionFactory == null || sessionFactory.isClosed()) {
            String dbPath = System.getenv("H2_DB_PATH");

            Configuration config = new Configuration().configure("hibernate.cfg.xml");

            if (dbPath != null && !dbPath.isBlank()) {
                config.setProperty("hibernate.connection.url",
                        "jdbc:h2:tcp://localhost:9092/" + dbPath);
            }

            sessionFactory = config.buildSessionFactory();
        }
        return sessionFactory;
    }

    public static void shutdown() {
        if (sessionFactory != null && !sessionFactory.isClosed()) {
            sessionFactory.close();
        }
    }
}