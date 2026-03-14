package edu.pucmm.eict.main;

import edu.pucmm.eict.main.modelos.*;
import edu.pucmm.eict.main.servicios.BootStrapServices;
import edu.pucmm.eict.main.servicios.HibernateUtil;
import edu.pucmm.eict.main.servicios.QRServices;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import io.javalin.rendering.template.JavalinThymeleaf;
import org.hibernate.Session;
import org.jasypt.util.password.StrongPasswordEncryptor;
import org.jasypt.util.text.AES256TextEncryptor;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.time.LocalDateTime;
import java.util.*;

public class Main {

    private static final StrongPasswordEncryptor passwordEncryptor = new StrongPasswordEncryptor();

    private static final AES256TextEncryptor cookieEncryptor = new AES256TextEncryptor();

    static {
        cookieEncryptor.setPassword("CLAVE_SECRETA_REMEMBERME");
    }

    static void main(String[] args) {

        BootStrapServices.init(passwordEncryptor);

        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("/templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode("HTML");
        resolver.setCharacterEncoding("UTF-8");

        TemplateEngine engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);

        var app = Javalin.create(config -> {
            config.staticFiles.add("/publico", Location.CLASSPATH); // ✅ ya lo tienes
            config.staticFiles.add("/templates", Location.CLASSPATH);

            config.fileRenderer(new JavalinThymeleaf(engine));

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

            config.routes.get("/", ctx -> ctx.redirect("login"));

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

                        if (remember) {
                            String encrypted = cookieEncryptor.encrypt(usuario.getUsername());
                            ctx.cookie("rememberMe", encrypted, 60 * 60 * 24 * 7);
                        }

                        if (usuario.getRol() == Rol.ADMIN || usuario.getRol() == Rol.ORGANIZADOR) {
                            ctx.redirect("/admin/eventos");
                        } else {
                            ctx.redirect("/eventos");
                        }

                    } else if (!usuarios.isEmpty() && usuarios.getFirst().isBloqueado()) {
                        // Usuario bloqueado
                        Map<String, Object> model = new HashMap<>();
                        model.put("error", "Tu cuenta ha sido bloqueada. Contacta al administrador.");

                        ctx.render("/Login.html", model);

                    } else {
                        Map<String, Object> model = new HashMap<>();
                        model.put("error", "Usuario o contraseña incorrectos");
                        ctx.render("/Login.html", model);
                    }
                }
            });

            config.routes.get("/logout", ctx -> {
                ctx.req().getSession().invalidate();
                ctx.removeCookie("rememberMe", "/");
                ctx.redirect("/login");
            });

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

                    String passwordHash = passwordEncryptor.encryptPassword(password);

                    session.beginTransaction();
                    session.persist(new Usuario(username, passwordHash, Rol.PARTICIPANTE));
                    session.getTransaction().commit();
                }

                ctx.redirect("/login");
            });

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
                    ctx.render("/Admin-Usuarios.html", Map.of("usuarios", usuarios));
                }
            });

            config.routes.patch("/admin/usuarios/{id}/rol", ctx -> {
                Usuario admin = ctx.sessionAttribute("usuario");
                if (admin == null || admin.getRol() != Rol.ADMIN) {
                    ctx.status(401).json(Map.of("error", "No autorizado"));
                    return;
                }

                Long id = Long.parseLong(ctx.pathParam("id"));
                @SuppressWarnings("unchecked")
                Map<String, Object> body = ctx.bodyAsClass(Map.class);
                String nuevoRol = body.get("rol").toString();

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

            config.routes.get("/admin/eventos", ctx -> {
                Usuario u = ctx.sessionAttribute("usuario");
                if (u == null || (u.getRol() != Rol.ADMIN && u.getRol() != Rol.ORGANIZADOR)) {
                    ctx.redirect("/login");
                    return;
                }
                try (Session session = HibernateUtil.getSessionFactory().openSession()) {
                    List<Evento> eventos = session
                            .createQuery("FROM Evento ORDER BY fechaHora DESC", Evento.class)
                            .getResultList();
                    ctx.render("Admin-Eventos.html", Map.of("eventos", eventos, "usuario", u));
                }
            });

            config.routes.get("/admin/eventos/nuevo", ctx -> {
                Usuario u = ctx.sessionAttribute("usuario");
                if (u == null || (u.getRol() != Rol.ADMIN && u.getRol() != Rol.ORGANIZADOR)) {
                    ctx.redirect("/login");
                    return;
                }
                ctx.render("Admin-Evento-Form.html", Map.of("usuario", u, "modo", "crear"));
            });

            config.routes.post("/admin/eventos", ctx -> {
                Usuario u = ctx.sessionAttribute("usuario");
                if (u == null || (u.getRol() != Rol.ADMIN && u.getRol() != Rol.ORGANIZADOR)) {
                    ctx.redirect("/login");
                    return;
                }

                String titulo = ctx.formParam("titulo");
                String descripcion = ctx.formParam("descripcion");
                String fechaStr = ctx.formParam("fechaHora");
                String lugar = ctx.formParam("lugar");
                int cupo = Integer.parseInt(Objects.requireNonNull(ctx.formParam("cupoMaximo")));
                String horasAperturaStr = ctx.formParam("horasApertura");
                int horasApertura = (horasAperturaStr != null && !horasAperturaStr.isBlank()) ? Integer.parseInt(horasAperturaStr) : 2;

                try (Session session = HibernateUtil.getSessionFactory().openSession()) {
                    Usuario organizador = session.find(Usuario.class, u.getId());
                    Evento evento = new Evento(titulo, descripcion, LocalDateTime.parse(fechaStr),
                            lugar, cupo, horasApertura, organizador);
                    session.beginTransaction();
                    session.persist(evento);
                    session.getTransaction().commit();
                }
                ctx.redirect("/admin/eventos");
            });
            config.routes.get("/admin/eventos/{id}/posponer", ctx -> {
                Usuario u = ctx.sessionAttribute("usuario");
                if (u == null || (u.getRol() != Rol.ADMIN && u.getRol() != Rol.ORGANIZADOR)) {
                    ctx.redirect("/login"); return;
                }
                Long id = Long.parseLong(ctx.pathParam("id"));
                try (Session session = HibernateUtil.getSessionFactory().openSession()) {
                    Evento evento = session.find(Evento.class, id);
                    if (evento == null) { ctx.status(404).result("Evento no encontrado"); return; }
                    if (evento.getEstado() == EstadoEvento.FINALIZADO || evento.getEstado() == EstadoEvento.CANCELADO) {
                        ctx.redirect("/admin/eventos"); return;
                    }
                    ctx.render("Admin-Evento-Form.html", Map.of("usuario", u, "evento", evento, "modo", "posponer"));
                }
            });

            config.routes.post("/admin/eventos/{id}/posponer", ctx -> {
                Usuario u = ctx.sessionAttribute("usuario");
                if (u == null || (u.getRol() != Rol.ADMIN && u.getRol() != Rol.ORGANIZADOR)) {
                    ctx.redirect("/login"); return;
                }
                Long id = Long.parseLong(ctx.pathParam("id"));
                try (Session session = HibernateUtil.getSessionFactory().openSession()) {
                    Evento evento = session.find(Evento.class, id);
                    if (evento == null) { ctx.status(404).result("Evento no encontrado"); return; }
                    if (evento.getEstado() == EstadoEvento.FINALIZADO || evento.getEstado() == EstadoEvento.CANCELADO) {
                        ctx.redirect("/admin/eventos"); return;
                    }
                    LocalDateTime nuevaFecha = LocalDateTime.parse(Objects.requireNonNull(ctx.formParam("fechaHora")));
                    if (!nuevaFecha.isAfter(LocalDateTime.now())) {
                        ctx.render("Admin-Evento-Form.html", Map.of(
                                "usuario", u, "evento", evento, "modo", "posponer",
                                "error", "La nueva fecha debe ser futura"
                        ));
                        return;
                    }
                    session.beginTransaction();
                    evento.setFechaHora(nuevaFecha);
                    String lugar = ctx.formParam("lugar");
                    if (lugar != null && !lugar.isBlank()) evento.setLugar(lugar);
                    evento.setEstado(EstadoEvento.POSPUESTO);
                    session.getTransaction().commit();
                }
                ctx.redirect("/admin/eventos");
            });


            config.routes.get("/admin/eventos/{id}/editar", ctx -> {
                Usuario u = ctx.sessionAttribute("usuario");
                if (u == null || (u.getRol() != Rol.ADMIN && u.getRol() != Rol.ORGANIZADOR)) {
                    ctx.redirect("/login");
                    return;
                }
                Long id = Long.parseLong(ctx.pathParam("id"));
                String modo = "posponer".equals(ctx.queryParam("modo")) ? "posponer" : "editar";

                try (Session session = HibernateUtil.getSessionFactory().openSession()) {
                    Evento evento = session.find(Evento.class, id);
                    if (evento == null) {
                        ctx.status(404).result("Evento no encontrado");
                        return;
                    }

                    // 🔒 BLOQUEO GENERAL: No editar nada si está FINALIZADO
                    if (evento.getEstado() == EstadoEvento.FINALIZADO) {
                        ctx.redirect("/admin/eventos");
                        return;
                    }

                    if ("editar".equals(modo) && (evento.getEstado() == EstadoEvento.PUBLICADO
                            || evento.getEstado() == EstadoEvento.POSPUESTO)) {
                        ctx.redirect("/admin/eventos");
                        return;
                    }

                    if ("posponer".equals(modo) && (evento.getEstado() == EstadoEvento.FINALIZADO
                            || evento.getEstado() == EstadoEvento.CANCELADO)) {
                        ctx.redirect("/admin/eventos");
                        return;
                    }

                    ctx.render("Admin-Evento-Form.html", Map.of("usuario", u, "evento", evento, "modo", modo));
                }
            });

            config.routes.post("/admin/eventos/{id}", ctx -> {
                Usuario u = ctx.sessionAttribute("usuario");
                if (u == null || (u.getRol() != Rol.ADMIN && u.getRol() != Rol.ORGANIZADOR)) {
                    ctx.redirect("/login");
                    return;
                }
                Long id = Long.parseLong(ctx.pathParam("id"));
                String modo = "posponer".equals(ctx.formParam("modo")) ? "posponer" : "editar";

                try (Session session = HibernateUtil.getSessionFactory().openSession()) {
                    Evento evento = session.find(Evento.class, id);
                    if (evento == null) {
                        ctx.status(404).result("Evento no encontrado");
                        return;
                    }

                   if (evento.getEstado() == EstadoEvento.FINALIZADO) {
                        ctx.redirect("/admin/eventos");
                        return;
                    }

                    session.beginTransaction();

                    if ("posponer".equals(modo)) {
                        if (evento.getEstado() == EstadoEvento.FINALIZADO || evento.getEstado() == EstadoEvento.CANCELADO) {
                            ctx.redirect("/admin/eventos");
                            return;
                        }

                        LocalDateTime nuevaFecha = LocalDateTime.parse(ctx.formParam("fechaHora"));
                        if (!nuevaFecha.isAfter(LocalDateTime.now())) {
                            ctx.status(400).result("La nueva fecha debe ser futura");
                            return;
                        }
                        evento.setFechaHora(nuevaFecha);
                        evento.setLugar(ctx.formParam("lugar"));
                        evento.setEstado(EstadoEvento.POSPUESTO);
                    } else {
                        // Modo editar
                        if (evento.getEstado() == EstadoEvento.PUBLICADO
                                || evento.getEstado() == EstadoEvento.POSPUESTO) {
                            ctx.redirect("/admin/eventos");
                            return;
                        }
                        evento.setTitulo(ctx.formParam("titulo"));
                        evento.setDescripcion(ctx.formParam("descripcion"));
                        evento.setFechaHora(LocalDateTime.parse(ctx.formParam("fechaHora")));
                        evento.setLugar(ctx.formParam("lugar"));
                        evento.setCupoMaximo(Integer.parseInt(ctx.formParam("cupoMaximo")));
                        String hAperturaStr = ctx.formParam("horasApertura");
                        evento.setHorasApertura((hAperturaStr != null && !hAperturaStr.isBlank()) ? Integer.parseInt(hAperturaStr) : 2);
                    }

                    session.getTransaction().commit();
                }
                ctx.redirect("/admin/eventos");
            });

            config.routes.patch("/admin/eventos/{id}/estado", ctx -> {
                Usuario u = ctx.sessionAttribute("usuario");
                if (u == null || (u.getRol() != Rol.ADMIN && u.getRol() != Rol.ORGANIZADOR)) {
                    ctx.status(401).json(Map.of("error", "No autorizado"));
                    return;
                }
                Long id = Long.parseLong(ctx.pathParam("id"));
                String nuevoEstado = ctx.bodyAsClass(Map.class).get("estado").toString();
                try {
                    EstadoEvento.valueOf(nuevoEstado);
                } catch (IllegalArgumentException e) {
                    ctx.status(400).json(Map.of("error", "Estado inválido"));
                    return;
                }

                try (Session session = HibernateUtil.getSessionFactory().openSession()) {
                    Evento evento = session.find(Evento.class, id);
                    if (evento == null) {
                        ctx.status(404).json(Map.of("error", "No encontrado"));
                        return;
                    }

                    if (nuevoEstado.equals("FINALIZADO") && LocalDateTime.now().isBefore(evento.getFechaHora())) {
                        ctx.status(400).json(Map.of("error", "No se puede finalizar un evento que aun no ha ocurrido"));
                        return;
                    }

                    if (evento.getEstado() == EstadoEvento.FINALIZADO) {
                        ctx.status(400).json(Map.of("error", "Un evento finalizado no puede cambiar de estado"));
                        return;
                    }

                    session.beginTransaction();
                    evento.setEstado(EstadoEvento.valueOf(nuevoEstado));
                    session.getTransaction().commit();
                    ctx.json(Map.of("ok", true, "estado", evento.getEstado().toString()));
                }
            });

            config.routes.get("/eventos", ctx -> {
                String busqueda = ctx.queryParam("q");
                try (Session session = HibernateUtil.getSessionFactory().openSession()) {
                    List<Evento> eventos;
                    if (busqueda != null && !busqueda.isBlank()) {
                        eventos = session.createQuery(
                                        "FROM Evento WHERE estado = :e AND LOWER(titulo) LIKE LOWER(:q) ORDER BY fechaHora ASC", Evento.class)
                                .setParameter("e", EstadoEvento.PUBLICADO)
                                .setParameter("q", "%" + busqueda.trim() + "%")
                                .getResultList();
                    } else {
                        eventos = session.createQuery(
                                        "FROM Evento WHERE estado = :e ORDER BY fechaHora ASC", Evento.class)
                                .setParameter("e", EstadoEvento.PUBLICADO)
                                .getResultList();
                    }

                    Usuario u = ctx.sessionAttribute("usuario");
                    Map<String, Object> model = new HashMap<>();
                    model.put("eventos", eventos);
                    model.put("usuario", u);
                    model.put("busqueda", busqueda != null ? busqueda : "");

                    Map<Long, Integer> cuposDisponibles = new HashMap<>();
                    for (Evento ev : eventos) {
                        long inscritos = session.createQuery(
                                        "SELECT COUNT(i) FROM Inscripcion i WHERE i.evento.id = :eid", Long.class)
                                .setParameter("eid", ev.getId()).getSingleResult();
                        cuposDisponibles.put(ev.getId(), (int) Math.max(0, ev.getCupoMaximo() - inscritos));
                    }
                    model.put("cuposDisponibles", cuposDisponibles);

                    if (u != null && u.getRol() == Rol.PARTICIPANTE) {
                        List<Long> ids = session
                                .createQuery("SELECT i.evento.id FROM Inscripcion i WHERE i.usuario.id = :uid", Long.class)
                                .setParameter("uid", u.getId()).getResultList();
                        model.put("inscritosIds", ids);
                    } else {
                        model.put("inscritosIds", List.of());
                    }
                    ctx.render("Eventos.html", model);
                }
            });

            config.routes.delete("/admin/eventos/{id}", ctx -> {
                Usuario u = ctx.sessionAttribute("usuario");
                if (u == null || u.getRol() != Rol.ADMIN) {
                    ctx.status(403).json(Map.of("error", "Solo el administrador puede eliminar eventos"));
                    return;
                }
                Long id = Long.parseLong(ctx.pathParam("id"));
                try (Session session = HibernateUtil.getSessionFactory().openSession()) {
                    Evento evento = session.find(Evento.class, id);
                    if (evento == null) {
                        ctx.status(404).json(Map.of("error", "Evento no encontrado"));
                        return;
                    }

                    if (evento.getEstado() == EstadoEvento.FINALIZADO) {
                        ctx.status(400).json(Map.of("error", "No se puede eliminar un evento finalizado"));
                        return;
                    }

                    session.beginTransaction();
                    session.createMutationQuery("DELETE FROM Inscripcion i WHERE i.evento.id = :eid")
                            .setParameter("eid", id).executeUpdate();
                    session.remove(evento);
                    session.getTransaction().commit();
                    ctx.json(Map.of("ok", true));
                }
            });

            // ── INSCRIPCIONES ──────────────────────────────────────────────
// Imports adicionales necesarios:
// import edu.pucmm.eict.main.modelos.Inscripcion;
// import edu.pucmm.eict.main.servicios.QRServices;

// ── INSCRIBIRSE A UN EVENTO ────────────────────────────────────
            config.routes.post("/inscripciones", ctx -> {
                Usuario u = ctx.sessionAttribute("usuario");
                if (u == null) {
                    ctx.status(401).json(Map.of("error", "Debes iniciar sesión"));
                    return;
                }
                if (u.getRol() != Rol.PARTICIPANTE) {
                    ctx.status(403).json(Map.of("error", "Solo los participantes pueden inscribirse"));
                    return;
                }

                Long eventoId = Long.parseLong(ctx.bodyAsClass(Map.class).get("eventoId").toString());

                try (Session session = HibernateUtil.getSessionFactory().openSession()) {
                    Evento evento = session.find(Evento.class, eventoId);

                    if (evento == null) {
                        ctx.status(404).json(Map.of("error", "Evento no encontrado"));
                        return;
                    }
                    if (evento.getEstado() != EstadoEvento.PUBLICADO) {
                        ctx.status(400).json(Map.of("error", "El evento no está disponible para inscripciones"));
                        return;
                    }

                    // Verificar inscripción duplicada
                    Long yaInscrito = session.createQuery(
                                    "SELECT COUNT(i) FROM Inscripcion i WHERE i.usuario.id = :uid AND i.evento.id = :eid", Long.class)
                            .setParameter("uid", u.getId())
                            .setParameter("eid", eventoId)
                            .uniqueResult();

                    if (yaInscrito > 0) {
                        ctx.status(409).json(Map.of("error", "Ya estás inscrito en este evento"));
                        return;
                    }

                    // Verificar cupo disponible
                    Long inscritos = session.createQuery(
                                    "SELECT COUNT(i) FROM Inscripcion i WHERE i.evento.id = :eid", Long.class)
                            .setParameter("eid", eventoId)
                            .uniqueResult();

                    if (inscritos >= evento.getCupoMaximo()) {
                        ctx.status(400).json(Map.of("error", "El evento no tiene cupos disponibles"));
                        return;
                    }

                    // Crear inscripción (el constructor genera el UUID del QR automáticamente)
                    Usuario participante = session.find(Usuario.class, u.getId());
                    Inscripcion inscripcion = new Inscripcion(participante, evento);

                    session.beginTransaction();
                    session.persist(inscripcion);
                    session.getTransaction().commit();

                    ctx.json(Map.of(
                            "ok", true,
                            "inscripcionId", inscripcion.getId(),
                            "codigoQr", inscripcion.getCodigoQr(),
                            "eventoTitulo", evento.getTitulo()
                    ));
                }
            });

// ── MIS INSCRIPCIONES (participante) ──────────────────────────
            config.routes.get("/mis-inscripciones", ctx -> {
                Usuario u = ctx.sessionAttribute("usuario");
                if (u == null) {
                    ctx.redirect("/login");
                    return;
                }
                if (u.getRol() != Rol.PARTICIPANTE) {
                    ctx.redirect("/eventos");
                    return;
                }

                try (Session session = HibernateUtil.getSessionFactory().openSession()) {
                    List<Inscripcion> inscripciones = session.createQuery(
                                    "FROM Inscripcion i LEFT JOIN FETCH i.evento WHERE i.usuario.id = :uid ORDER BY i.id DESC",
                                    Inscripcion.class)
                            .setParameter("uid", u.getId())
                            .getResultList();

                    ctx.render("mis-inscripciones.html", Map.of("inscripciones", inscripciones, "usuario", u));
                }
            });

// ── CANCELAR INSCRIPCIÓN ───────────────────────────────────────
            config.routes.delete("/inscripciones/{id}", ctx -> {
                Usuario u = ctx.sessionAttribute("usuario");
                if (u == null) {
                    ctx.status(401).json(Map.of("error", "No autenticado"));
                    return;
                }

                Long id = Long.parseLong(ctx.pathParam("id"));

                try (Session session = HibernateUtil.getSessionFactory().openSession()) {
                    Inscripcion inscripcion = session.find(Inscripcion.class, id);

                    if (inscripcion == null) {
                        ctx.status(404).json(Map.of("error", "Inscripción no encontrada"));
                        return;
                    }
                    // Solo el propio participante puede cancelar su inscripción
                    if (!inscripcion.getUsuario().getId().equals(u.getId())) {
                        ctx.status(403).json(Map.of("error", "No autorizado"));
                        return;
                    }
                    // No se puede cancelar si el evento ya fue finalizado
                    if (inscripcion.getEvento().getEstado() == EstadoEvento.FINALIZADO) {
                        ctx.status(400).json(Map.of("error", "No se puede cancelar una inscripción de un evento finalizado"));
                        return;
                    }
                    // FIX: no se puede cancelar si ya se registró asistencia
                    if (inscripcion.isAsistencia()) {
                        ctx.status(400).json(Map.of("error", "No se puede cancelar una inscripción con asistencia registrada"));
                        return;
                    }

                    session.beginTransaction();
                    session.remove(inscripcion);
                    session.getTransaction().commit();

                    ctx.json(Map.of("ok", true));
                }
            });

// ── VER QR DE UNA INSCRIPCIÓN ──────────────────────────────────
            config.routes.get("/inscripciones/{id}/qr", ctx -> {
                Usuario u = ctx.sessionAttribute("usuario");
                if (u == null) {
                    ctx.redirect("/login");
                    return;
                }

                Long id = Long.parseLong(ctx.pathParam("id"));

                try (Session session = HibernateUtil.getSessionFactory().openSession()) {
                    Inscripcion inscripcion = session.find(Inscripcion.class, id);

                    if (inscripcion == null) {
                        ctx.status(404).result("Inscripción no encontrada");
                        return;
                    }

                    // Validar que el usuario sea el dueño de la inscripción
                    if (!inscripcion.getUsuario().getId().equals(u.getId())) {
                        ctx.status(403).result("No autorizado - Esta inscripción no te pertenece");
                        return;
                    }

                    // ✅ CORRECTO: Usar generarQRBytesEstructurado con 2 argumentos
                    byte[] qr = QRServices.generarQRBytesEstructurado(
                            inscripcion.getCodigoQr(),           // String: UUID de la inscripción
                            inscripcion.getEvento().getId()      // Long: ID del evento
                    );

                    ctx.contentType("image/png")
                            .header("Content-Disposition", "inline; filename=\"qr-" + inscripcion.getCodigoQr() + ".png\"")
                            .result(qr);
                }
            });
// ── LISTA DE INSCRIPCIONES POR EVENTO (admin/organizador) ──────
            config.routes.get("/admin/eventos/{id}/inscripciones", ctx -> {
                Usuario u = ctx.sessionAttribute("usuario");
                if (u == null || (u.getRol() != Rol.ADMIN && u.getRol() != Rol.ORGANIZADOR)) {
                    ctx.redirect("/login");
                    return;
                }

                Long eventoId = Long.parseLong(ctx.pathParam("id"));

                try (Session session = HibernateUtil.getSessionFactory().openSession()) {
                    Evento evento = session.find(Evento.class, eventoId);
                    if (evento == null) {
                        ctx.status(404).result("Evento no encontrado");
                        return;
                    }

                    List<Inscripcion> inscripciones = session.createQuery(
                                    "FROM Inscripcion i JOIN FETCH i.usuario WHERE i.evento.id = :eid ORDER BY i.id",
                                    Inscripcion.class)
                            .setParameter("eid", eventoId)
                            .getResultList();

                    long totalInscritos = inscripciones.size();
                    long asistieron = inscripciones.stream().filter(Inscripcion::isAsistencia).count();

                    ctx.render("admin-inscripciones.html", Map.of(
                            "evento", evento,
                            "inscripciones", inscripciones,
                            "totalInscritos", totalInscritos,
                            "asistieron", asistieron,
                            "usuario", u
                    ));
                }
            });
// ── MARCAR ASISTENCIA (escaneo QR) ────────────────────────────
            config.routes.post("/admin/asistencia", ctx -> {
                Usuario u = ctx.sessionAttribute("usuario");
                if (u == null || (u.getRol() != Rol.ADMIN && u.getRol() != Rol.ORGANIZADOR)) {
                    ctx.status(401).json(Map.of("error", "No autorizado"));
                    return;
                }

                @SuppressWarnings("unchecked")
                Map<String, Object> body = ctx.bodyAsClass(Map.class);

                String qrCrudo = body.get("codigoQr") != null ? body.get("codigoQr").toString().trim() : null;

                Long eventoIdEsperado = null;
                if (body.get("eventoId") != null) {
                    try {
                        eventoIdEsperado = Long.parseLong(body.get("eventoId").toString());
                    } catch (NumberFormatException e) {
                        ctx.status(400).json(Map.of("error", "ID de evento inválido"));
                        return;
                    }
                }

                if (qrCrudo == null || qrCrudo.isEmpty()) {
                    ctx.status(400).json(Map.of("error", "Código QR requerido"));
                    return;
                }

                // OPCIÓN B: Parsear QR estructurado
                QRServices.ResultadoQR parseado = QRServices.parsearQREstructurado(qrCrudo);
                Long eventoIdDelQr = parseado.eventoId;
                String codigoQr = parseado.codigoQr;

                // Validar que el QR tenga estructura correcta
                if (eventoIdDelQr == null) {
                    ctx.status(400).json(Map.of(
                            "error", "Formato de QR inválido",
                            "detalle", "El QR no contiene información del evento. Asegúrate de usar un QR válido del sistema."
                    ));
                    return;
                }

                // Validar que coincida con el evento donde se escanea
                if (eventoIdEsperado != null && !eventoIdDelQr.equals(eventoIdEsperado)) {
                    ctx.status(403).json(Map.of(
                            "error", "QR no válido para este evento",
                            "detalle", "Este QR pertenece al evento ID: " + eventoIdDelQr +
                                    ", pero estás escaneando en el evento ID: " + eventoIdEsperado,
                            "eventoEsperado", eventoIdEsperado,
                            "eventoReal", eventoIdDelQr
                    ));
                    return;
                }

                try (Session session = HibernateUtil.getSessionFactory().openSession()) {
                    List<Inscripcion> resultado = session.createQuery(
                                    "FROM Inscripcion i JOIN FETCH i.usuario JOIN FETCH i.evento WHERE i.codigoQr = :qr",
                                    Inscripcion.class)
                            .setParameter("qr", codigoQr)
                            .getResultList();

                    if (resultado.isEmpty()) {
                        ctx.status(404).json(Map.of("error", "Código QR no válido o no encontrado"));
                        return;
                    }

                    Inscripcion inscripcion = resultado.getFirst();
                    Evento evento = inscripcion.getEvento();
                    Usuario participante = inscripcion.getUsuario();

                    // Validar consistencia
                    if (!evento.getId().equals(eventoIdDelQr)) {
                        ctx.status(403).json(Map.of(
                                "error", "Inconsistencia de datos",
                                "detalle", "El QR indica evento ID: " + eventoIdDelQr +
                                        ", pero la inscripción pertenece al evento ID: " + evento.getId()
                        ));
                        return;
                    }

                    // Validaciones de estado, puertas, bloqueo, asistencia...
                    if (evento.getEstado() == EstadoEvento.CANCELADO) {
                        ctx.status(400).json(Map.of("error", "El evento fue cancelado"));
                        return;
                    }

                    if (evento.getEstado() == EstadoEvento.BORRADOR) {
                        ctx.status(400).json(Map.of("error", "El evento no está publicado"));
                        return;
                    }

                    if (!evento.isPuertasAbiertas()) {
                        LocalDateTime apertura = evento.getFechaHora().minusHours(evento.getHorasApertura());
                        long minutosRestantes = java.time.Duration.between(LocalDateTime.now(), apertura).toMinutes();
                        ctx.status(400).json(Map.of(
                                "error", "Las puertas aún no están abiertas",
                                "minutosRestantes", Math.abs(minutosRestantes),
                                "horaApertura", apertura.toString()
                        ));
                        return;
                    }

                    if (participante.isBloqueado()) {
                        ctx.status(403).json(Map.of(
                                "error", "Participante bloqueado",
                                "participante", participante.getUsername()
                        ));
                        return;
                    }

                    if (inscripcion.isAsistencia()) {
                        ctx.json(Map.of(
                                "ok", false,
                                "mensaje", "Asistencia ya registrada anteriormente",
                                "participante", participante.getUsername(),
                                "evento", evento.getTitulo(),
                                "horaRegistro", inscripcion.getHoraAsistencia() != null ?
                                        inscripcion.getHoraAsistencia().toString() : "desconocida"
                        ));
                        return;
                    }

                    session.beginTransaction();
                    boolean registrada = inscripcion.marcarAsistencia();
                    if (registrada) {
                        session.merge(inscripcion);
                        session.getTransaction().commit();

                        ctx.json(Map.of(
                                "ok", true,
                                "mensaje", "Asistencia registrada correctamente",
                                "participante", participante.getUsername(),
                                "evento", evento.getTitulo(),
                                "horaRegistro", inscripcion.getHoraAsistencia().toString()
                        ));
                    } else {
                        session.getTransaction().rollback();
                        ctx.json(Map.of(
                                "ok", false,
                                "mensaje", "La asistencia ya había sido registrada",
                                "participante", participante.getUsername()
                        ));
                    }
                }
            });

// ── RESUMEN / ESTADÍSTICAS DEL EVENTO ─────────────────────────
            config.routes.get("/admin/eventos/{id}/resumen", ctx -> {
                Usuario u = ctx.sessionAttribute("usuario");
                if (u == null || (u.getRol() != Rol.ADMIN && u.getRol() != Rol.ORGANIZADOR)) {
                    ctx.redirect("/login");
                    return;
                }
                Long id = Long.parseLong(ctx.pathParam("id"));
                try (Session session = HibernateUtil.getSessionFactory().openSession()) {
                    Evento evento = session.find(Evento.class, id);
                    if (evento == null) {
                        ctx.status(404).result("No encontrado");
                        return;
                    }
                    ctx.render("Admin-Evento-Resumen.html", Map.of("evento", evento, "usuario", u));
                }
            });

// ── API: datos de estadísticas (Fetch desde el frontend) ───────
            config.routes.get("/admin/eventos/{id}/stats", ctx -> {
                Usuario u = ctx.sessionAttribute("usuario");
                if (u == null || (u.getRol() != Rol.ADMIN && u.getRol() != Rol.ORGANIZADOR)) {
                    ctx.status(401).json(Map.of("error", "No autorizado"));
                    return;
                }
                Long id = Long.parseLong(ctx.pathParam("id"));
                try (Session session = HibernateUtil.getSessionFactory().openSession()) {
                    Evento evento = session.find(Evento.class, id);
                    if (evento == null) {
                        ctx.status(404).json(Map.of("error", "No encontrado"));
                        return;
                    }

                    List<Inscripcion> inscripciones = session.createQuery(
                                    "FROM Inscripcion i WHERE i.evento.id = :eid", Inscripcion.class)
                            .setParameter("eid", id).getResultList();

                    long totalInscritos = inscripciones.size();
                    long totalAsistentes = inscripciones.stream().filter(Inscripcion::isAsistencia).count();
                    double porcentaje = totalInscritos > 0 ? (totalAsistentes * 100.0 / totalInscritos) : 0;

                    // ── Inscripciones por día ───────────────────────────────
                    // Agrupar por fecha (yyyy-MM-dd) de fechaInscripcion
                    java.util.TreeMap<String, Long> mapDia = new java.util.TreeMap<>();
                    java.time.format.DateTimeFormatter fmtDia = java.time.format.DateTimeFormatter.ofPattern("dd/MM");
                    for (Inscripcion i : inscripciones) {
                        if (i.getFechaInscripcion() != null) {
                            String dia = i.getFechaInscripcion().format(fmtDia);
                            mapDia.merge(dia, 1L, Long::sum);
                        }
                    }
                    List<Map<String, Object>> porDia = mapDia.entrySet().stream()
                            .map(e2 -> {
                                Map<String, Object> m = new HashMap<>();
                                m.put("fecha", e2.getKey());
                                m.put("total", e2.getValue());
                                return m;
                            })
                            .collect(java.util.stream.Collectors.toList());

                    // ── Asistencia por hora ─────────────────────────────────
                    // Agrupar por hora (0–23) de horaAsistencia
                    java.util.TreeMap<Integer, Long> mapHora = new java.util.TreeMap<>();
                    for (Inscripcion i : inscripciones) {
                        if (i.isAsistencia() && i.getHoraAsistencia() != null) {
                            int hora = i.getHoraAsistencia().getHour();
                            mapHora.merge(hora, 1L, Long::sum);
                        }
                    }
                    List<Map<String, Object>> porHora = mapHora.entrySet().stream()
                            .map(e2 -> {
                                Map<String, Object> m = new HashMap<>();
                                m.put("hora", e2.getKey());
                                m.put("total", e2.getValue());
                                return m;
                            })
                            .collect(java.util.stream.Collectors.toList());

                    Map<String, Object> stats = new HashMap<>();
                    stats.put("totalInscritos", totalInscritos);
                    stats.put("totalAsistentes", totalAsistentes);
                    stats.put("porcentaje", Math.round(porcentaje * 10.0) / 10.0);
                    stats.put("cupoMaximo", evento.getCupoMaximo());
                    stats.put("disponibles", evento.getCupoMaximo() - totalInscritos);
                    stats.put("inscripcionesPorDia", porDia);
                    stats.put("asistenciaPorHora", porHora);

                    ctx.json(stats);
                }
            });

            // DEBUG: Generar QR de prueba y mostrarlo (temporal)
            // DEBUG: Generar QR de prueba - VERSIÓN ROBUSTA
            config.routes.get("/debug/qr-test", ctx -> {
                String testUuid = "550e8400-e29b-41d4-a716-446655440000";
                Long testEventoId = 1L;

                byte[] qr = QRServices.generarQRBytesEstructurado(testUuid, testEventoId);
                String base64 = java.util.Base64.getEncoder().encodeToString(qr);

                // HTML con verificación explícita de jsQR
                String html = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="UTF-8">
            <title>Test QR</title>
            <style>
                body { 
                    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; 
                    padding: 20px; 
                    max-width: 600px; 
                    margin: 0 auto;
                    line-height: 1.6;
                }
                h2 { color: #333; }
                .section { 
                    background: #f8f9fa; 
                    padding: 15px; 
                    border-radius: 8px; 
                    margin: 15px 0;
                    border-left: 4px solid #007bff;
                }
                #qr-container { 
                    text-align: center; 
                    padding: 20px;
                    background: white;
                    border-radius: 8px;
                    box-shadow: 0 2px 4px rgba(0,0,0,0.1);
                }
                #qr-image { max-width: 100%; height: auto; }
                button { 
                    background: #007bff; 
                    color: white; 
                    border: none; 
                    padding: 12px 24px; 
                    border-radius: 6px;
                    cursor: pointer;
                    font-size: 16px;
                    margin: 5px;
                }
                button:hover { background: #0056b3; }
                #resultado { 
                    padding: 15px; 
                    margin-top: 15px; 
                    border-radius: 6px;
                    display: none;
                    font-family: monospace;
                    font-size: 14px;
                }
                .ok { background: #d4edda; color: #155724; border: 1px solid #c3e6cb; }
                .error { background: #f8d7da; color: #721c24; border: 1px solid #f5c6cb; }
                .info { background: #d1ecf1; color: #0c5460; border: 1px solid #bee5eb; }
                code { 
                    background: #f4f4f4; 
                    padding: 2px 6px; 
                    border-radius: 3px;
                    font-family: 'Courier New', monospace;
                }
                #log { 
                    font-family: monospace; 
                    font-size: 12px; 
                    color: #666;
                    margin-top: 20px;
                    padding: 10px;
                    background: #f8f9fa;
                    border-radius: 4px;
                    max-height: 200px;
                    overflow-y: auto;
                }
                .log-entry { margin: 2px 0; }
            </style>
        </head>
        <body>
            <h2>🔍 Test de QR - Debug</h2>
            
            <div class="section">
                <strong>Contenido esperado:</strong> <code>E1:550e8400-e29b-41d4-a716-446655440000</code><br>
                <strong>Tamaño:</strong> 40 caracteres (optimizado para QR)
            </div>
            
            <div id="qr-container">
                <img id="qr-image" src="data:image/png;base64,%s" width="350" alt="QR de prueba">
                <p style="color: #666; font-size: 14px; margin-top: 10px;">
                    👆 Este QR debería contener el código formateado
                </p>
            </div>
            
            <div style="text-align: center;">
                <button onclick="verificarLibreria()">1️⃣ Verificar jsQR</button>
                <button onclick="analizarQR()">2️⃣ Analizar QR</button>
            </div>
            
            <div id="resultado"></div>
            
            <div class="section" style="border-left-color: #28a745;">
                <h4>📤 Subir imagen QR externa:</h4>
                <input type="file" id="file-input" accept="image/*" onchange="analizarArchivo(this)">
            </div>
            
            <div id="log"></div>

            <!-- Cargar jsQR desde múltiples fuentes como fallback -->
            <script src="https://cdn.jsdelivr.net/npm/jsqr@1.4.0/dist/jsQR.js"></script>
            
            <script>
                // Verificar que jsQR cargó correctamente
                window.jsqrLoaded = (typeof jsQR !== 'undefined');
                
                function log(msg) {
                    const time = new Date().toLocaleTimeString();
                    const entry = document.createElement('div');
                    entry.className = 'log-entry';
                    entry.textContent = '[' + time + '] ' + msg;
                    document.getElementById('log').appendChild(entry);
                    console.log('[QR Debug]', msg);
                }
                
                function mostrarResultado(html, tipo) {
                    const div = document.getElementById('resultado');
                    div.innerHTML = html;
                    div.className = tipo;
                    div.style.display = 'block';
                    log('Resultado mostrado: ' + tipo);
                }
                
                function verificarLibreria() {
                    log('Verificando librería jsQR...');
                    
                    const tipo = typeof jsQR;
                    const disponible = (tipo !== 'undefined');
                    
                    let html = '<h3>ℹ️ Verificación de jsQR</h3>';
                    html += '<p><strong>typeof jsQR:</strong> <code>' + tipo + '</code></p>';
                    html += '<p><strong>Disponible:</strong> ' + (disponible ? '✅ SÍ' : '❌ NO') + '</p>';
                    
                    if (disponible) {
                        html += '<p style="color: #155724;">✅ La librería está cargada correctamente</p>';
                        mostrarResultado(html, 'ok');
                    } else {
                        html += '<p style="color: #721c24;">❌ jsQR no está disponible. Intenta recargar la página.</p>';
                        html += '<p>URL intentada: <code>https://cdn.jsdelivr.net/npm/jsqr@1.4.0/dist/jsQR.js</code></p>';
                        mostrarResultado(html, 'error');
                    }
                    
                    log('jsQR disponible: ' + disponible);
                }
                
                function analizarQR() {
                    log('Iniciando análisis de QR...');
                    
                    // Verificar jsQR primero
                    if (typeof jsQR === 'undefined') {
                        mostrarResultado(
                            '<h3>❌ Error: jsQR no disponible</h3>' +
                            '<p>La librería jsQR no se cargó. Haz clic en "Verificar jsQR" primero.</p>',
                            'error'
                        );
                        return;
                    }
                    
                    const img = document.getElementById('qr-image');
                    log('Imagen natural: ' + img.naturalWidth + 'x' + img.naturalHeight);
                    
                    if (!img.naturalWidth) {
                        mostrarResultado('<h3>❌ Error</h3><p>La imagen no se cargó correctamente</p>', 'error');
                        return;
                    }
                    
                    // Crear canvas oculto
                    const canvas = document.createElement('canvas');
                    const ctx = canvas.getContext('2d');
                    
                    // Probar múltiples escalas
                    const escalas = [2, 1.5, 1, 0.8];
                    let codigoDetectado = null;
                    let escalaUsada = 0;
                    
                    for (let escala of escalas) {
                        const w = Math.floor(img.naturalWidth * escala);
                        const h = Math.floor(img.naturalHeight * escala);
                        
                        canvas.width = w;
                        canvas.height = h;
                        ctx.imageSmoothingEnabled = false;
                        ctx.drawImage(img, 0, 0, w, h);
                        
                        log('Probando escala ' + escala + ': ' + w + 'x' + h);
                        
                        try {
                            const imageData = ctx.getImageData(0, 0, w, h);
                            log('ImageData obtenido: ' + imageData.width + 'x' + imageData.height);
                            
                            // LLAMADA CORRECTA: jsQR en minúsculas
                            const resultado = jsQR(
                                imageData.data, 
                                imageData.width, 
                                imageData.height,
                                { inversionAttempts: "attemptBoth" }
                            );
                            
                            log('jsQR resultado: ' + (resultado ? 'DETECTADO' : 'null'));
                            
                            if (resultado && resultado.data) {
                                codigoDetectado = resultado.data;
                                escalaUsada = escala;
                                break;
                            }
                        } catch (e) {
                            log('Error en escala ' + escala + ': ' + e.message);
                        }
                    }
                    
                    if (codigoDetectado) {
                        const esEstructurado = codigoDetectado.startsWith('E');
                        let html = '<h3>✅ QR Detectado</h3>';
                        html += '<p><strong>Escala usada:</strong> ' + escalaUsada + 'x</p>';
                        html += '<p><strong>Contenido:</strong></p>';
                        html += '<code style="display: block; padding: 10px; background: #f8f9fa; word-break: break-all;">';
                        html += escapeHtml(codigoDetectado);
                        html += '</code>';
                        html += '<p><strong>Formato estructurado:</strong> ' + (esEstructurado ? '✅ SÍ' : '❌ NO') + '</p>';
                        
                        if (esEstructurado) {
                            const partes = codigoDetectado.split(':');
                            html += '<p><strong>Evento ID:</strong> ' + partes[0].substring(1) + '</p>';
                            html += '<p><strong>UUID:</strong> ' + partes[1] + '</p>';
                        }
                        
                        mostrarResultado(html, 'ok');
                    } else {
                        mostrarResultado(
                            '<h3>❌ No se detectó QR</h3>' +
                            '<p>Intentado escalas: ' + escalas.join(', ') + '</p>' +
                            '<p>Posibles causas:</p>' +
                            '<ul>' +
                            '<li>El QR está muy comprimido</li>' +
                            '<li>El formato no es compatible con jsQR</li>' +
                            '<li>Intenta descargar la imagen y subirla manualmente</li>' +
                            '</ul>',
                            'error'
                        );
                    }
                }
                
                function analizarArchivo(input) {
                    const file = input.files[0];
                    if (!file) {
                        log('No se seleccionó archivo');
                        return;
                    }
                    
                    log('Archivo: ' + file.name + ' (' + file.type + ', ' + file.size + ' bytes)');
                    
                    const reader = new FileReader();
                    reader.onload = function(e) {
                        const img = new Image();
                        img.onload = function() {
                            log('Imagen cargada: ' + img.width + 'x' + img.height);
                            
                            const canvas = document.createElement('canvas');
                            const ctx = canvas.getContext('2d');
                            
                            const escalas = [2, 1.5, 1];
                            let detectado = false;
                            
                            for (let escala of escalas) {
                                const w = Math.floor(img.width * escala);
                                const h = Math.floor(img.height * escala);
                                
                                canvas.width = w;
                                canvas.height = h;
                                ctx.imageSmoothingEnabled = false;
                                ctx.drawImage(img, 0, 0, w, h);
                                
                                const imageData = ctx.getImageData(0, 0, w, h);
                                const code = jsQR(imageData.data, imageData.width, imageData.height, {
                                    inversionAttempts: "attemptBoth"
                                });
                                
                                log('Escala ' + escala + ': ' + (code ? 'OK' : 'NO'));
                                
                                if (code && code.data) {
                                    let html = '<h3>✅ QR Detectado (archivo)</h3>';
                                    html += '<p><strong>Escala:</strong> ' + escala + '</p>';
                                    html += '<code style="display: block; padding: 10px; background: #f8f9fa; word-break: break-all;">';
                                    html += escapeHtml(code.data);
                                    html += '</code>';
                                    mostrarResultado(html, 'ok');
                                    detectado = true;
                                    break;
                                }
                            }
                            
                            if (!detectado) {
                                mostrarResultado('<h3>❌ No detectado</h3><p>Intentado escalas: ' + escalas.join(', ') + '</p>', 'error');
                            }
                        };
                        img.src = e.target.result;
                    };
                    reader.readAsDataURL(file);
                }
                
                function escapeHtml(text) {
                    const div = document.createElement('div');
                    div.textContent = text;
                    return div.innerHTML;
                }
                
                // Auto-verificar al cargar
                window.onload = function() {
                    log('Página cargada, jsQR disponible: ' + (typeof jsQR !== 'undefined'));
                };
            </script>
        </body>
        </html>
        """.formatted(base64);

                ctx.contentType("text/html").result(html);
            });
        }).start(7070);

        System.out.println("[Javalin] Servidor iniciado en http://localhost:7070");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[App] Apagando la aplicación...");
            app.stop();
            BootStrapServices.shutdown();
        }));
    }
}