// array-utils.js — reusable helpers
function filterByField(arr, field, value) {
  var result = [];
  for (var i = 0; i < arr.length; i++) {
    if (arr[i][field] === value) {
      result.push(arr[i]);
    }
  }
  return result;
}

function pluckFields(arr, fields) {
  var result = [];
  for (var i = 0; i < arr.length; i++) {
    var obj = {};
    for (var j = 0; j < fields.length; j++) {
      obj[fields[j]] = arr[i][fields[j]];
    }
    result.push(obj);
  }
  return result;
}
