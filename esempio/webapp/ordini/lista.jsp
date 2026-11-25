<%@ page contentType="text/html; charset=UTF-8" %>
<%@ taglib prefix="fmt" uri="http://java.sun.com/jsp/jstl/fmt" %>
<html>
<head><title><fmt:message key="ordini.titolo"/></title></head>
<body>
<h1>Ordini aperti</h1>
<%
  java.sql.Statement st = conn.createStatement();
  java.sql.ResultSet rs = st.executeQuery("SELECT o.ID_ORDINE, c.RAGIONE_SOCIALE, o.TOTALE FROM V_ORDINI_APERTI o JOIN CLIENTE c ON 1=1");
%>
<table>
  <tr><th>Numero</th><th>Cliente</th><th>Totale</th></tr>
</table>
<a href="nuovo.jsp">Nuovo ordine</a>
</body>
</html>
