package edu.pucmm.eict.main;

import edu.pucmm.eict.main.modelos.Rol;
import edu.pucmm.eict.main.modelos.Usuario;
import edu.pucmm.eict.main.servicios.BootStrapServices;
import edu.pucmm.eict.main.servicios.HibernateUtil;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import io.javalin.rendering.template.JavalinThymeleaf;
import org.hibernate.Session;
import org.jasypt.util.password.StrongPasswordEncryptor;
import org.jasypt.util.text.AES256TextEncryptor;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Main {

    // Hash unidireccional para contraseñas (no reversible)
    private static final StrongPasswordEncryptor passwordEncryptor = new StrongPasswordEncryptor();

    // AES256 bidireccional solo para la cookie "rememberMe"
    private static final AES256TextEncryptor cookieEncryptor = new AES256TextEncryptor();

    static {
        cookieEncryptor.setPassword("CLAVE_SECRETA_REMEMBERME");
    }

    static void main(String[] args) {

        // 1. Inicializar H2, Hibernate y el usuario admin por defecto
        BootStrapServices.init(passwordEncryptor);

        // 2. Configurar Thymeleaf manualmente
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("/templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode("HTML");
        resolver.setCharacterEncoding("UTF-8");

        TemplateEngine engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);

        // 3. Crear la aplicación Javalin
        var app = Javalin.create(config -> {

            config.staticFiles.add("/templates", Location.CLASSPATH);

            // Registrar Thymeleaf como motor de plantillas
            config.fileRenderer(new JavalinThymeleaf(engine));

            // ── MIDDLEWARE: AutoLogin desde cookie ─────────────────
            config.routes.before(ctx -> {
                if (ctx.sessionAttribute("usuario") == null) {
                    String cookie = ctx.cookie("rememberMe");
                    if (cookie != null) {
                        try {
                            String username = cookieEncryptor.decrypt(cookie);
                            try (Session session = HibernateUtil.getSessionFactory().openSession()) {
                                List<Usuario> usuarios = session
                                        .createQuery("FROM Usuario WHERE username = :u", Usuario.class)
                                        .setParameter("u", username)
                                        .getResultList();
                                if (!usuarios.isEmpty()) {
                                    ctx.sessionAttribute("usuario", usuarios.getFirst());
                                }
                            }
                        } catch (Exception ignored) {
                            // Cookie inválida o expirada
                            ctx.removeCookie("rememberMe", "/");
                        }
                    }
                }
            });

            // ── INICIO ─────────────────────────────────────────────
            config.routes.get("/", ctx -> ctx.result("Servidor corriendo correctamente"));

            // ── LOGIN ──────────────────────────────────────────────
            config.routes.get("/login", ctx ->
                    ctx.render("/Login.html"));

            config.routes.post("/login", ctx -> {
                String username = ctx.formParam("username");
                String password = ctx.formParam("password");
                boolean remember = ctx.formParam("remember") != null;

                try (Session session = HibernateUtil.getSessionFactory().openSession()) {
                    List<Usuario> usuarios = session
                            .createQuery("FROM Usuario WHERE username = :u", Usuario.class)
                            .setParameter("u", username)
                            .getResultList();

                    if (!usuarios.isEmpty()
                            && passwordEncryptor.checkPassword(password, usuarios.getFirst().getPassword())
                            && !usuarios.getFirst().isBloqueado()) {

                        Usuario usuario = usuarios.getFirst();
                        ctx.sessionAttribute("usuario", usuario);

                        // Guardar cookie cifrada por 7 días si marcó "Recordarme"
                        if (remember) {
                            String encrypted = cookieEncryptor.encrypt(usuario.getUsername());
                            ctx.cookie("rememberMe", encrypted, 60 * 60 * 24 * 7);
                        }

                        if (usuario.getRol() == Rol.ADMIN || usuario.getRol() == Rol.ORGANIZADOR) {
                            ctx.redirect("/dashboard");
                        } else {
                            ctx.redirect("/");
                        }

                    } else if (!usuarios.isEmpty() && usuarios.getFirst().isBloqueado()) {
                        // Usuario bloqueado
                        Map<String, Object> model = new HashMap<>();
                        model.put("error", "Tu cuenta ha sido bloqueada. Contacta al administrador.");

                        ctx.render("/login.html", model);

                    } else {
                        // Credenciales incorrectas
                        Map<String, Object> model = new HashMap<>();
                        model.put("error", "Usuario o contraseña incorrectos");
                        ctx.render("/login.html", model);
                    }
                }
            });

            // ── LOGOUT ─────────────────────────────────────────────
            config.routes.get("/logout", ctx -> {
                ctx.req().getSession().invalidate();
                ctx.removeCookie("rememberMe", "/");
                ctx.redirect("/login");
            });

            // ── REGISTRO ───────────────────────────────────────────
            config.routes.get("/registro", ctx ->
                    ctx.render("/Registro.html"));

            config.routes.post("/registro", ctx -> {
                String username = ctx.formParam("username");
                String password = ctx.formParam("password");

                try (Session session = HibernateUtil.getSessionFactory().openSession()) {
                    Long existe = session
                            .createQuery("SELECT COUNT(u) FROM Usuario u WHERE u.username = :u", Long.class)
                            .setParameter("u", username)
                            .uniqueResult();

                    if (existe > 0) {
                        Map<String, Object> model = new HashMap<>();
                        model.put("error", "El nombre de usuario ya está en uso");
                        ctx.render("/Registro.html", model);
                        return;
                    }

                    // Hashear la contraseña antes de guardar
                    String passwordHash = passwordEncryptor.encryptPassword(password);

                    session.beginTransaction();
                    session.persist(new Usuario(username, passwordHash, Rol.PARTICIPANTE));
                    session.getTransaction().commit();
                }

                ctx.redirect("/login");
            });

            // ── ADMIN: GESTIÓN DE USUARIOS ─────────────────────────────
// Agregar estas rutas dentro del bloque Javalin.create(config -> { ... })

// Vista principal
            config.routes.get("/admin/usuarios", ctx -> {
                Usuario admin = ctx.sessionAttribute("usuario");
                if (admin == null || admin.getRol() != Rol.ADMIN) {
                    ctx.redirect("/login");
                    return;
                }
                try (Session session = HibernateUtil.getSessionFactory().openSession()) {
                    List<Usuario> usuarios = session
                            .createQuery("FROM Usuario ORDER BY id", Usuario.class)
                            .getResultList();
                    ctx.render("/admin-usuarios.html", Map.of("usuarios", usuarios));
                }
            });

// PATCH /admin/usuarios/{id}/rol — cambia el rol a cualquiera de los 3 roles
            config.routes.patch("/admin/usuarios/{id}/rol", ctx -> {
                Usuario admin = ctx.sessionAttribute("usuario");
                if (admin == null || admin.getRol() != Rol.ADMIN) {
                    ctx.status(401).json(Map.of("error", "No autorizado"));
                    return;
                }

                Long id = Long.parseLong(ctx.pathParam("id"));
                String nuevoRol = ctx.bodyAsClass(Map.class).get("rol").toString();

                // Validar que el rol sea válido antes de hacer la query
                try {
                    Rol.valueOf(nuevoRol);
                } catch (IllegalArgumentException e) {
                    ctx.status(400).json(Map.of("error", "Rol inválido: " + nuevoRol));
                    return;
                }

                try (Session session = HibernateUtil.getSessionFactory().openSession()) {
                    Usuario usuario = session.find(Usuario.class, id);

                    if (usuario == null) {
                        ctx.status(404).json(Map.of("error", "Usuario no encontrado"));
                        return;
                    }
                    if (usuario.getUsername().equals("admin")) {
                        ctx.status(403).json(Map.of("error", "No se puede modificar el usuario administrador"));
                        return;
                    }

                    session.beginTransaction();
                    usuario.setRol(Rol.valueOf(nuevoRol));
                    session.getTransaction().commit();

                    ctx.json(Map.of("ok", true, "nuevoRol", usuario.getRol().toString()));
                }
            });

// PATCH /admin/usuarios/{id}/bloqueo — toggle bloqueado/activo
            config.routes.patch("/admin/usuarios/{id}/bloqueo", ctx -> {
                Usuario admin = ctx.sessionAttribute("usuario");
                if (admin == null || admin.getRol() != Rol.ADMIN) {
                    ctx.status(401).json(Map.of("error", "No autorizado"));
                    return;
                }

                Long id = Long.parseLong(ctx.pathParam("id"));

                try (Session session = HibernateUtil.getSessionFactory().openSession()) {
                    Usuario usuario = session.find(Usuario.class, id);

                    if (usuario == null) {
                        ctx.status(404).json(Map.of("error", "Usuario no encontrado"));
                        return;
                    }
                    if (usuario.getUsername().equals("admin")) {
                        ctx.status(403).json(Map.of("error", "No se puede bloquear al administrador"));
                        return;
                    }

                    session.beginTransaction();
                    usuario.setBloqueado(!usuario.isBloqueado());
                    session.getTransaction().commit();

                    ctx.json(Map.of("ok", true, "bloqueado", usuario.isBloqueado()));
                }
            });

        }).start(7070);

        System.out.println("[Javalin] Servidor iniciado en http://localhost:7070");

        // 4. Cierre limpio al detener la app (Ctrl+C)
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[App] Apagando la aplicación...");
            app.stop();
            BootStrapServices.shutdown();
        }));
    }
}