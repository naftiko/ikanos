// active-members.js — uses helpers from array-utils.js
var members = context['list-members'];
var active = filterByField(members, 'type', 'User');
result = pluckFields(active, ['login', 'id']);
