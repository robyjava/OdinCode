package it.acme.vendite.web;

import javax.servlet.http.*;
import it.acme.vendite.servizi.OrdineService;

@WebServlet("/ordini")
public class OrdineServlet extends HttpServlet {
    private OrdineService service;

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) {
        try {
            service.nuovoOrdine(Long.parseLong(req.getParameter("cliente")), 10.0);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
