package edu.pucmm.eict.main.servicios;

import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;

public class HibernateUtil {

    private static SessionFactory sessionFactory;

    private HibernateUtil() {
    }

    public static synchronized SessionFactory getSessionFactory() {
        if (sessionFactory == null || sessionFactory.isClosed()) {
            String dbUrl = System.getenv("DB_URL");
            String dbUser = System.getenv("DB_USER");
            String dbPassword = System.getenv("DB_PASSWORD");

            Configuration config = new Configuration().configure("hibernate.cfg.xml");

            if (dbUrl != null && !dbUrl.isBlank()) {
                config.setProperty("hibernate.connection.url", dbUrl);
            }
            if (dbUser != null && !dbUser.isBlank()) {
                config.setProperty("hibernate.connection.username", dbUser);
            }
            if (dbPassword != null) {
                config.setProperty("hibernate.connection.password", dbPassword);
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