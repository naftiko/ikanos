// filter-active.js — filters active users from a list
var members = context['list-members'];
var active = [];
for (var i = 0; i < members.length; i++) {
  if (members[i].type === 'User') {
    active.push({ login: members[i].login, id: members[i].id });
  }
}
result = active;
