package edu.pucmm.eict.main.modelos;

public enum EstadoEvento {
    BORRADOR,    // creado pero no visible para participantes
    PUBLICADO,   // visible e inscribible
    CANCELADO,   // visible pero no se puede inscribir
    FINALIZADO   // el evento ya ocurrió
}