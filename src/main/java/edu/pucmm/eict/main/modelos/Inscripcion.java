package edu.pucmm.eict.main.modelos;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "inscripciones")
public class Inscripcion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "evento_id", nullable = false)
    private Evento evento;

    @Column(nullable = false, unique = true, length = 36)
    private String codigoQr;

    @Column(nullable = false)
    private boolean asistencia = false;

    // Fecha en que se inscribió — para gráfico "inscripciones por día"
    @Column(nullable = false)
    private LocalDateTime fechaInscripcion;

    // Hora en que se registró la asistencia — para gráfico "asistencia por hora"
    @Column
    private LocalDateTime horaAsistencia;

    // Constructor vacío requerido por JPA
    public Inscripcion() {}

    public Inscripcion(Usuario usuario, Evento evento) {
        this.usuario = usuario;
        this.evento = evento;
        this.codigoQr = UUID.randomUUID().toString();
        this.fechaInscripcion = LocalDateTime.now();
    }

    // Getters
    public Long getId() { return id; }
    public Usuario getUsuario() { return usuario; }
    public Evento getEvento() { return evento; }
    public String getCodigoQr() { return codigoQr; }
    public boolean isAsistencia() { return asistencia; }
    public LocalDateTime getFechaInscripcion() { return fechaInscripcion; }
    public LocalDateTime getHoraAsistencia() { return horaAsistencia; }

    /**
     * Registra la asistencia con la hora actual.
     * Si ya tenía asistencia, no modifica la hora original (preserva primera marcada).
     * @return true si se registró nueva asistencia, false si ya existía
     */
    public boolean marcarAsistencia() {
        if (this.asistencia) {
            return false; // Ya estaba marcada
        }
        this.asistencia = true;
        this.horaAsistencia = LocalDateTime.now();
        return true;
    }

    /**
     * Quita la marca de asistencia (para correcciones de administrador).
     */
    public void quitarAsistencia() {
        this.asistencia = false;
        this.horaAsistencia = null;
    }

    /**
     * Setter manual para casos especiales (carga de datos, tests).
     * Preferir usar marcarAsistencia() en producción.
     */
    public void setAsistencia(boolean asistencia) {
        if (asistencia) {
            this.marcarAsistencia();
        } else {
            this.quitarAsistencia();
        }
    }

    public void setCodigoQr(String codigoQr) { this.codigoQr = codigoQr; }
    public void setFechaInscripcion(LocalDateTime fechaInscripcion) {
        this.fechaInscripcion = fechaInscripcion;
    }

    /**
     * Setter manual para hora (usar con precaución, preferir marcarAsistencia).
     */
    public void setHoraAsistencia(LocalDateTime horaAsistencia) {
        this.horaAsistencia = horaAsistencia;
    }

    @Override
    public String toString() {
        return "Inscripcion{" +
                "id=" + id +
                ", usuario=" + usuario.getUsername() +
                ", evento=" + evento.getTitulo() +
                ", asistencia=" + asistencia +
                ", horaAsistencia=" + horaAsistencia +
                '}';
    }
}