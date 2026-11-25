<%@ page contentType="text/html; charset=UTF-8" %>
<html>
<head><title>Nuovo ordine</title>
<script>
function controlla() {
  if (document.f.cliente.value == '') { alert('Indicare il codice cliente'); return false; }
  return confirm('Confermi l\'inserimento dell\'ordine?');
}
</script>
</head>
<body>
<form name="f" method="post" action="${pageContext.request.contextPath}/ordini" onsubmit="return controlla()">
  <table>
    <tr><td><label for="cli">Codice cliente</label></td><td><input type="text" id="cli" name="cliente"/></td></tr>
    <tr><td>Totale:</td><td><input type="text" name="totale"/></td></tr>
    <tr><td>Note</td><td><textarea name="note"></textarea></td></tr>
    <input type="hidden" name="azione" value="nuovo"/>
  </table>
  <input type="submit" value="Salva ordine"/>
</form>
<a href="lista.jsp">Torna all'elenco</a>
</body>
</html>
