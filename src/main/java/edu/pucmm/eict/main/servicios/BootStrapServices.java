package edu.pucmm.eict.main.servicios;

import edu.pucmm.eict.main.modelos.Rol;
import edu.pucmm.eict.main.modelos.Usuario;
import org.h2.tools.Server;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.jasypt.util.password.StrongPasswordEncryptor;

public class BootStrapServices {

    private static Server h2Server;

    public static void init(StrongPasswordEncryptor passwordEncryptor) {
        iniciarServidorH2();
        inicializarHibernate();
        crearAdminPorDefecto(passwordEncryptor);
    }

    private static void iniciarServidorH2() {
        try {
            h2Server = Server.createTcpServer(
                    "-tcpPort", "9092",
                    "-tcpAllowOthers",
                    "-ifNotExists"
            ).start();
            // En iniciarServidorH2(), después de crear el TCP server:
            Server.createWebServer("-webPort", "8082", "-webAllowOthers").start();

            System.out.println("[H2] Servidor iniciado — estado: " + h2Server.getStatus());
            Thread.sleep(500);

        } catch (Exception e) {
            throw new RuntimeException("[H2] No se pudo iniciar el servidor: " + e.getMessage(), e);
        }
    }

    private static void inicializarHibernate() {
        try {
            HibernateUtil.getSessionFactory();
            System.out.println("[Hibernate] SessionFactory inicializada correctamente");
        } catch (Exception e) {
            throw new RuntimeException("No se pudo inicializar Hibernate", e);
        }
    }

    private static void crearAdminPorDefecto(StrongPasswordEncryptor passwordEncryptor) {
        SessionFactory sf = HibernateUtil.getSessionFactory();

        try (Session session = sf.openSession()) {
            Long cantidadAdmins = session
                    .createQuery("SELECT COUNT(u) FROM Usuario u WHERE u.rol = :rol", Long.class)
                    .setParameter("rol", Rol.ADMIN)
                    .uniqueResult();

            if (cantidadAdmins == 0) {
                // Encriptar la contraseña del admin antes de guardarla
                String passwordEncriptado = passwordEncryptor.encryptPassword("admin");

                session.beginTransaction();
                session.persist(new Usuario("admin", passwordEncriptado, Rol.ADMIN));
                session.getTransaction().commit();

                System.out.println("[Bootstrap] Usuario ADMIN creado: admin / admin123");
            } else {
                System.out.println("[Bootstrap] Ya existe un ADMIN, no se crea uno nuevo");
            }
        } catch (Exception e) {
            throw new RuntimeException("Error en Bootstrap", e);
        }
    }

    public static void shutdown() {
        HibernateUtil.shutdown();
        if (h2Server != null) {
            h2Server.stop();
            System.out.println("[H2] Servidor detenido");
        }
    }
}