<%--

    Copyright © 2019 Jesse Gallagher

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

--%>
<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" session="false" %>
<%@taglib prefix="t" tagdir="/WEB-INF/tags" %>
<%@taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<t:layout>
	<table>
		<thead>
			<tr>
				<th>${translation.groupId}</th>
				<th>${translation.uri}</th>
				<th></th>
			</tr>
		</thead>
		<tbody>
		<c:forEach items="${repos.repositories}" var="repo">
			<tr>
				<td>${repo.groupId}</td>
				<td>${repo.uri}</td>
				<td>
					<form method="POST" action="admin/repos/${repo.id}" enctype="application/x-www-form-urlencoded">
						<input type="submit" value="${translation.deleteButton}"/>
						<input type="hidden" name="_method" value="DELETE" />
					</form>
				</td>
			</tr>
		</c:forEach>
		</tbody>
	</table>
	
	<fieldset>
		<legend>${translation.addRepo}</legend>
		
		<form method="POST" action="admin/repos" enctype="application/x-www-form-urlencoded">
			<p>
				<label for="createGroupId">${translation.groupId} <input type="text" name="groupId" id="createGroupId"/></label>
			</p>
			<p>
				<label for="createUri">${translation.uri} <input type="text" name="uri" id="createUri"/></label>
			</p>
			<input type="submit" value="${translation.create}"/>
		</form>
	</fieldset>
</t:layout>