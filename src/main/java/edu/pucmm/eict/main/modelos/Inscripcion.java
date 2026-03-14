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

    @Column(nullable = false)
    private LocalDateTime fechaInscripcion;

    @Column
    private LocalDateTime horaAsistencia;

    public Inscripcion() {}

    public Inscripcion(Usuario usuario, Evento evento) {
        this.usuario = usuario;
        this.evento = evento;
        this.codigoQr = UUID.randomUUID().toString();
        this.fechaInscripcion = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Usuario getUsuario() { return usuario; }
    public Evento getEvento() { return evento; }
    public String getCodigoQr() { return codigoQr; }
    public boolean isAsistencia() { return asistencia; }
    public LocalDateTime getFechaInscripcion() { return fechaInscripcion; }
    public LocalDateTime getHoraAsistencia() { return horaAsistencia; }

    public boolean marcarAsistencia() {
        if (this.asistencia) {
            return false; // Ya estaba marcada
        }
        this.asistencia = true;
        this.horaAsistencia = LocalDateTime.now();
        return true;
    }

    public void quitarAsistencia() {
        this.asistencia = false;
        this.horaAsistencia = null;
    }

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