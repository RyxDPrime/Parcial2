package edu.pucmm.eict.main.modelos;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "inscripciones")
public class Inscripcion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Relación con Usuario: muchas inscripciones pertenecen a un usuario
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    // Relación con Evento: muchas inscripciones pertenecen a un evento
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "evento_id", nullable = false)
    private Evento evento;

    // Código QR único generado automáticamente al crear la inscripción
    @Column(nullable = false, unique = true)
    private String codigoQr;

    // false = no ha asistido, true = asistió
    @Column(nullable = false)
    private boolean asistencia = false;

    // Constructor vacío requerido por Hibernate
    public Inscripcion() {}

    public Inscripcion(Usuario usuario, Evento evento) {
        this.usuario = usuario;
        this.evento = evento;
        // El código QR se genera automáticamente como UUID único
        this.codigoQr = UUID.randomUUID().toString();
    }

    // Getters y Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Usuario getUsuario() { return usuario; }
    public void setUsuario(Usuario usuario) { this.usuario = usuario; }

    public Evento getEvento() { return evento; }
    public void setEvento(Evento evento) { this.evento = evento; }

    public String getCodigoQr() { return codigoQr; }
    public void setCodigoQr(String codigoQr) { this.codigoQr = codigoQr; }

    public boolean isAsistencia() { return asistencia; }
    public void setAsistencia(boolean asistencia) { this.asistencia = asistencia; }

    @Override
    public String toString() {
        return "Inscripcion{id=" + id + ", codigoQr='" + codigoQr + "', asistencia=" + asistencia + "}";
    }
}