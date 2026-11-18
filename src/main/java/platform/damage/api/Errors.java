package platform.damage.api;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import jakarta.ws.rs.ext.*;
import jakarta.persistence.OptimisticLockException;
import java.sql.SQLException;
import java.sql.SQLTransientException;
import jakarta.resource.ResourceException;
import java.util.Map;
@Provider
public class Errors implements ExceptionMapper<Exception> {
    public Response toResponse(Exception error) {
        int status=500;
        for(Throwable cause=error;cause!=null;cause=cause.getCause()) {
            if(cause instanceof WebApplicationException web) {status=web.getResponse().getStatus();break;}
            if(cause instanceof OptimisticLockException) {status=409;break;}
            if(cause instanceof ResourceException&&cause.getMessage()!=null&&cause.getMessage().contains("IJ000655"))status=503;
            if(cause instanceof SQLException sql) {
                if("23505".equals(sql.getSQLState()))status=409;
                else if(sql instanceof SQLTransientException||sql.getSQLState()!=null&&(sql.getSQLState().startsWith("08")||java.util.Set.of("53300","57P01","57P02","57P03").contains(sql.getSQLState())))status=503;
            }
        }
        return Response.status(status).type(MediaType.APPLICATION_JSON).entity(Map.of("status",status,"detail",switch(status) {
            case 400 -> "Solicitud inválida";case 401 -> "Autenticación requerida";case 403 -> "Acceso insuficiente";
            case 404 -> "Recurso inexistente o no visible";case 409 -> "Conflicto de identidad o versión";
            case 413 -> "Solicitud demasiado grande";case 422 -> "Regla de negocio incumplida";
            case 503 -> "Dependencia temporalmente indisponible";default -> "Error interno";
        })).build();
    }
}
