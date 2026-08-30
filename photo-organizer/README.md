# OrdenaFotos Socio

## v1.1
- análisis persistente mediante foreground service
- continúa con pantalla bloqueada
- checkpoint por foto en SQLite
- pausa, continuación y detención desde notificación
- vista previa ligera para bibliotecas grandes

## v1.2.1
- sincroniza por MediaStore ID las fotos ya movidas
- importa y valida el respaldo ordenafotos.db desde el selector de archivos
- la cola de pendientes excluye las fotos ya presentes en Pictures/OrdenaFotos
- firma de compilación fijada explícitamente a la clave persistente del proyecto
